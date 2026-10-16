package jp.co.translacat.languagelearning.features.webaiplan.infrastructure.persistence

import jp.co.translacat.languagelearning.features.learner.domain.exception.LearnerUnavailableException
import jp.co.translacat.languagelearning.features.settings.application.GetOrCreateUserSettings
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.ExposedSettingsUnitOfWork
import jp.co.translacat.languagelearning.features.webaiplan.application.*
import jp.co.translacat.languagelearning.features.webaiplan.domain.model.*
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.flywaydb.core.Flyway
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** 일반 catalog와 실모델을 사용하지 않고 LocalScratchMysql이 만든 임시 catalog만 검증한다. */
class WebAiPersistenceIntegrationTest {
    private val now = Instant.parse("2026-10-15T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val keywords = listOf(WebAiKeyword("SYSTEM:1", "요건정의", "requirements"))
    private val context = WebAiContextProvider { WebAiContext("ko", "ja", "ko", keywords, keywords) }

    @Test
    fun `V037에서 V038로 이행하며 기존 설정과 네 개의 저장 테이블을 유지한다`() {
        LocalScratchMysql.use { db ->
            // 준비: 기존 schema까지만 올리고 실제 설정 값을 넣는다.
            val settings = db.settings()
            Flyway.configure().dataSource(settings.jdbcUrl, settings.username, settings.password)
                .locations("classpath:db/migration").schemas(db.name).defaultSchema(db.name)
                .createSchemas(false).cleanDisabled(true).baselineOnMigrate(false).target("037").load().migrate()
            execute(db, "UPDATE language_learning_admin_setting SET default_daily_sentence_count=17 WHERE id='DEFAULT'")

            // 실행
            DatabaseFactory(settings).use { factory ->
                // 검증: 기존 기록을 수정하지 않고 신규 migration만 한 번 적용한다.
                assertEquals(1, factory.migrationReport.migrationsExecuted)
                assertEquals(38, factory.migrationReport.schemaVersion.toInt())
                assertEquals(17L, scalar(db, "SELECT default_daily_sentence_count FROM language_learning_admin_setting WHERE id='DEFAULT'"))
                assertEquals(4L, scalar(db, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('ll_web_ai_plans','ll_web_ai_runs','ll_web_ai_results','ll_web_ai_items')"))
            }
        }
    }

    @Test
    fun `두 풀의 같은 실행과 제출은 한 번만 평가하고 성장 점수도 한 번만 반영한다`() = withDatabase { db, factory ->
        DatabaseFactory(db.settings()).use { otherFactory ->
            // 준비: 별도 pool 두 개가 같은 사용자의 learner 행 잠금을 공유한다.
            val calls = AtomicInteger()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val verifier: suspend (WebAiVerificationInput) -> WebAiVerifiedItem = { input ->
                calls.incrementAndGet()
                started.complete(Unit)
                release.await()
                verified(input.item)
            }
            val first = service(factory, verifier)
            val second = service(otherFactory, verifier)
            val plan = first.save(123, null, change())
            val runs = coroutineScope {
                listOf(async { first.run(123, plan.id, "same-execution") },
                    async { second.run(123, plan.id, "same-execution") }).awaitAll()
            }
            val submission = submission(runs.first())

            // 실행: 첫 평가가 진행 중일 때 같은 제출을 다른 pool에서 재전송한다.
            val firstResult = coroutineScope {
                val pending = async { first.submit(123, submission) }
                withTimeout(10_000) { started.await() }
                val duplicate = second.submit(123, submission)
                assertEquals("VERIFYING", duplicate.status)
                release.complete(Unit)
                pending.await().also { assertEquals(duplicate.id, it.id) }
            }
            val retried = second.retry(123, firstResult.id)
            val repeated = second.submit(123, submission)

            // 검증: 원문 표와 일본어가 그대로 저장되며 중복 요청은 추가 비용·점수를 만들지 않는다.
            assertEquals(runs[0].id, runs[1].id)
            assertEquals(firstResult.id, repeated.id)
            assertEquals("VERIFIED", retried.status)
            assertEquals(submission.originalFeedbackMarkdown, repeated.originalFeedbackMarkdown)
            assertEquals(1, calls.get())
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM ll_web_ai_runs"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM ll_web_ai_results"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM ll_web_ai_items"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM language_learning_activity"))
            assertEquals(1L, scalar(db, "SELECT evaluation_count FROM language_learning_profile WHERE user_id=123"))
            assertEquals(5L, scalar(db, "SELECT COUNT(*) FROM language_learning_metric_history"))
            assertEquals("WEB_AI_CONFLICT", assertFailsWith<WebAiException> {
                first.submit(123, submission.copy(originalFeedbackMarkdown = "changed"))
            }.code)
            assertEquals("WEB_AI_CONFLICT", assertFailsWith<WebAiException> {
                first.submit(123, submission.copy(submissionId = "different-submission"))
            }.code)
        }
    }

    @Test
    fun `성장 기록 뒤 저장 오류가 나면 결과와 프로필 활동이 모두 롤백된다`() = withDatabase { db, factory ->
        // 준비
        val store = store(factory)
        val service = service(factory)
        val plan = service.save(123, null, change())
        val run = service.run(123, plan.id, "atomic-run")
        val item = verified(submission(run).items.single())
        val resultId = UUID.randomUUID().toString()
        val result = WebAiResult(resultId, run.id, 123, "VERIFIED", "합성 원문", listOf(item),
            now.toString(), now.toString(), "ko", "ja", "ko", run.learningDate, run.planName)

        // 실행: 실제 저장 트랜잭션의 마지막 구역에서 실패를 발생시킨다.
        assertFailsWith<IllegalStateException> {
            store.write(123) {
                saveResult(WebAiStoredResult(result, "atomic-submission", "a".repeat(64), null, null))
                reserveItems(run.id, result.id, listOf(item.item.itemId))
                applyGrowth(run, result.id, item, now)
                error("synthetic transaction failure")
            }
        }

        // 검증: 결과·문항 예약·성장 반영이 함께 사라져 정상 재제출이 가능하다.
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM ll_web_ai_results"))
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM ll_web_ai_items"))
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_activity"))
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_profile"))
        assertEquals("VERIFIED", service.submit(123, submission(run)).status)
        assertEquals(1L, scalar(db, "SELECT evaluation_count FROM language_learning_profile WHERE user_id=123"))
    }

    @Test
    fun `연결 키 회전과 폐기는 과거 키를 거부하고 다른 사용자의 기록을 숨긴다`() = withDatabase { db, factory ->
        // 준비
        seed(db, factory, 456)
        val service = service(factory)
        val plan = service.save(123, null, change())
        val old = service.connection(123, plan.id)
        val token = service.connection(123, plan.id)

        // 실행 및 검증: 이전 키와 다른 사용자는 소유자의 계획·실행·결과에 접근할 수 없다.
        assertEquals(401, assertFailsWith<WebAiException> { service.externalRun(old, "external-1") }.status)
        val run = service.externalRun(token, "external-1")
        val result = service.externalSubmit(token, submission(run))
        assertEquals(404, assertFailsWith<WebAiException> { service.runById(456, run.id) }.status)
        assertEquals(404, assertFailsWith<WebAiException> { service.result(456, result.id) }.status)
        assertEquals(404, assertFailsWith<WebAiException> { service.connection(456, plan.id) }.status)
        assertEquals(404, assertFailsWith<WebAiException> { service.delete(456, plan.id) }.status)
        assertEquals(409, assertFailsWith<WebAiException> { service.save(456, null, change().copy(id = plan.id)) }.status)
        assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM ll_web_ai_plans"))
        assertTrue(service.results(456, 20).isEmpty())
        service.revoke(123, plan.id)
        assertEquals(401, assertFailsWith<WebAiException> { service.externalRun(token, "external-2") }.status)
        assertEquals(result.id, service.result(123, result.id).id)
    }

    @Test
    fun `외부 실행 키를 바꿔도 같은 일정의 문항 할당을 다시 만들지 않는다`() = withDatabase { db, factory ->
        // 준비
        val service = service(factory)
        val plan = service.save(123, null, change())
        val token = service.connection(123, plan.id)

        // 실행
        val first = service.externalRun(token, "arbitrary-first")
        val second = service.externalRun(token, "arbitrary-second")

        // 검증: 연결 키 보유자가 실행 키만 바꿔 유료 검증 문항을 무한 증식할 수 없다.
        assertEquals(first.id, second.id)
        assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM ll_web_ai_runs"))
    }

    @Test
    fun `기본 학습 언어 변경은 같은 일정에서도 새 실행을 만들고 이전 언어 점수는 합산하지 않는다`() = withDatabase { db, factory ->
        // 준비
        val learning = AtomicReference("ja")
        val calls = AtomicInteger()
        val changingContext = WebAiContextProvider { WebAiContext("ko", learning.get(), "ko", keywords, keywords) }
        val service = WebAiPlanService(store(factory), changingContext, {
            calls.incrementAndGet()
            verified(it.item)
        }, clock)
        val plan = service.save(123, null, change())
        val token = service.connection(123, plan.id)
        val japanese = service.externalRun(token, "same-occurrence")

        // 실행: 기본 설정이 바뀌어도 계획 revision이나 일정 회차를 억지로 바꾸지 않는다.
        execute(db, "UPDATE language_learning_user_setting SET learning_language='en' WHERE user_id=123")
        learning.set("en")
        val english = service.externalRun(token, "same-occurrence")

        // 검증: 새 언어 문제는 받을 수 있고 과거 언어의 답안은 공식 점수에 반영하지 않는다.
        assertNotEquals(japanese.id, english.id)
        assertEquals("en", english.learningLanguage)
        assertEquals("WEB_AI_LANGUAGE_CHANGED", assertFailsWith<WebAiException> {
            service.submit(123, submission(japanese))
        }.code)
        assertEquals(0, calls.get())
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_activity"))
    }

    @Test
    fun `비활성 학습자는 저장된 원문과 피드백도 조회할 수 없다`() = withDatabase { db, factory ->
        // 준비
        val service = service(factory)
        val plan = service.save(123, null, change())
        val run = service.run(123, plan.id, "before-disabled")
        val result = service.submit(123, submission(run))
        val store = store(factory)
        execute(db, "UPDATE language_learning_learner SET status='DISABLED' WHERE user_id=123")

        // 실행 / 검증: 목록, 제한 목록, 상세 조회가 동일한 학습자 상태 경계를 사용한다.
        assertFailsWith<LearnerUnavailableException> { store.results(123) }
        assertFailsWith<LearnerUnavailableException> { store.recentResults(123, 20) }
        assertFailsWith<LearnerUnavailableException> { store.result(123, result.id) }
    }

    @Test
    fun `비동기 제출은 평가 전에 저장되고 새 풀의 작업자가 사용자별 대기 결과를 한 번만 확정한다`() = withDatabase { db, factory ->
        // 준비: HTTP 요청은 Provider를 기다리지 않고 원문과 문항 예약을 먼저 저장한다.
        val calls = AtomicInteger()
        val verifier: suspend (WebAiVerificationInput) -> WebAiVerifiedItem = {
            calls.incrementAndGet()
            verified(it.item)
        }
        val queueStore = store(factory)
        val queued = WebAiPlanService(queueStore, context, verifier, clock, asynchronous = true)
        val plan = queued.save(123, null, change(count = 2))
        val run = queued.run(123, plan.id, "queued-run")
        val first = queued.submit(123, submission(run))
        val second = queued.submit(123, submission(run).copy(submissionId = "second-batch", items = listOf(
            submission(run).items.single().copy(itemId = "WRITING_TRANSLATION:STANDARD:2"))))
        assertEquals("PENDING", first.status)
        assertEquals("PENDING", second.status)
        assertEquals(0, calls.get())
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_activity"))

        // 실행: 새 pool과 서비스 인스턴스가 DB 대기열을 재발견하고 선택한 결과만 처리한다.
        DatabaseFactory(db.settings()).use { otherFactory ->
            val recoveredStore = store(otherFactory)
            val candidates = recoveredStore.pendingCandidates(now, 100)
            assertEquals(1, candidates.size)
            assertEquals(123L, candidates.single().first)
            val selected = candidates.single().second
            assertTrue(selected in setOf(first.id, second.id))
            val recovery = WebAiPlanService(recoveredStore, context, verifier, clock, asynchronous = true)
            val completed = recovery.processQueued(123, selected)
            val repeated = recovery.processQueued(123, selected)

            // 검증: 이미 완료한 문항은 재평가하지 않고 공식 성장 기록도 중복 반영하지 않는다.
            assertEquals("VERIFIED", completed.status)
            assertEquals(completed, repeated)
            assertEquals(1, calls.get())
            assertEquals(submission(run).originalFeedbackMarkdown, repeated.originalFeedbackMarkdown)
            assertEquals(1L, scalar(db, "SELECT evaluation_count FROM language_learning_profile WHERE user_id=123"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM language_learning_activity"))
            assertEquals(1, recoveredStore.pendingCandidates(now, 100).size)
        }
    }

    @Test
    fun `세 번 시도 후 만료된 비동기 lease는 추가 평가 없이 실패로 종결한다`() = withDatabase { db, factory ->
        // 준비: 프로세스 중단으로 마지막 시도의 lease만 남은 상태를 실제 DB에 저장한다.
        val calls = AtomicInteger()
        val store = store(factory)
        val service = WebAiPlanService(store, context, {
            calls.incrementAndGet()
            verified(it.item)
        }, clock, asynchronous = true)
        val plan = service.save(123, null, change())
        val run = service.run(123, plan.id, "expired-queue")
        val pending = service.submit(123, submission(run))
        store.write(123) {
            val saved = checkNotNull(result(pending.id))
            saveResult(saved.copy(result = saved.result.copy(status = "VERIFYING"), attempts = 3,
                leaseId = UUID.randomUUID().toString(), leaseUntil = now.minusSeconds(1)))
        }
        assertEquals(listOf(123L to pending.id), store.pendingCandidates(now, 100))

        // 실행
        val failed = service.processQueued(123, pending.id)

        // 검증: 대기열에서 사라지고 원문은 보존되며 비용이나 공식 점수를 추가하지 않는다.
        assertEquals("FAILED", failed.status)
        assertEquals("WEB_AI_RETRY_LIMIT", failed.items.single().reason)
        assertEquals(submission(run).originalFeedbackMarkdown, failed.originalFeedbackMarkdown)
        assertTrue(store.pendingCandidates(now, 100).isEmpty())
        assertEquals(0, calls.get())
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_activity"))
        store.write(123) {
            val saved = checkNotNull(result(pending.id))
            assertEquals(3, saved.attempts)
            assertNull(saved.leaseId)
            assertNull(saved.leaseUntil)
        }
    }

    @Test
    fun `다른 결과 검증 중에는 새 제출을 막고 실패한 검증은 세 번까지만 시도한다`() = withDatabase { db, factory ->
        // 준비
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val service = service(factory) {
            calls.incrementAndGet()
            started.complete(Unit)
            release.await()
            throw IllegalStateException("synthetic verifier failure")
        }
        val plan = service.save(123, null, change(count = 2))
        val run = service.run(123, plan.id, "busy-run")

        // 실행: 진행 중인 작업과 다른 문항의 제출을 경합시킨다.
        val failed = coroutineScope {
            val first = async { service.submit(123, submission(run)) }
            withTimeout(10_000) { started.await() }
            val other = submission(run).copy(submissionId = "other", items = listOf(
                submission(run).items.single().copy(itemId = "WRITING_TRANSLATION:STANDARD:2")))
            assertEquals(429, assertFailsWith<WebAiException> { service.submit(123, other) }.status)
            release.complete(Unit)
            first.await()
        }
        service.retry(123, failed.id)
        service.retry(123, failed.id)

        // 검증: 재시도 한도 이후에도 원문은 남고 평가 호출 수는 증가하지 않는다.
        assertEquals("WEB_AI_RETRY_LIMIT", assertFailsWith<WebAiException> { service.retry(123, failed.id) }.code)
        assertEquals(3, calls.get())
        assertEquals(submission(run).originalFeedbackMarkdown, service.result(123, failed.id).originalFeedbackMarkdown)
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_activity"))
    }

    private fun change(count: Long = 1) = WebAiPlanChange(
        name = "실무 일본어", timezone = "Asia/Tokyo", time = "18:00", recurrence = "DAILY",
        areas = listOf(WebAiArea("WRITING_TRANSLATION", 0, count, 0)),
    )

    private fun submission(run: WebAiRun) = WebAiResultSubmission(
        run.id, "submission-1", "## 원문 피드백\n|작성|교정|\n|特定な|特定の|\n自然な日本語。",
        listOf(WebAiSubmittedItem("WRITING_TRANSLATION:STANDARD:1", "WRITING_TRANSLATION", "STANDARD",
            "자료를 공유드립니다.", "資料を共有いたします。", sourceText = "자료를 공유드립니다.", externalScore = 99.0)),
    )

    private fun verified(item: WebAiSubmittedItem) = WebAiVerifiedItem(item, "VERIFIED", 80.0,
        buildJsonObject { put("summary", "합성 검증 피드백") },
        listOf("MEANING", "GRAMMAR", "VOCABULARY", "NATURALNESS", "EXPRESSION").map { WebAiMetric(it, 80.0) },
        List(5) { 80.0 })

    private fun store(factory: DatabaseFactory) = ExposedWebAiStore(JdbcTransactionRunner(factory.database, 4))

    private fun service(factory: DatabaseFactory,
                        verifier: suspend (WebAiVerificationInput) -> WebAiVerifiedItem = { verified(it.item) }) =
        WebAiPlanService(store(factory), context, verifier, clock)

    private fun withDatabase(block: suspend (LocalScratchMysql, DatabaseFactory) -> Unit) {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { factory ->
                runBlocking {
                    seed(db, factory, 123)
                    block(db, factory)
                }
            }
        }
    }

    private suspend fun seed(db: LocalScratchMysql, factory: DatabaseFactory, user: Long) {
        GetOrCreateUserSettings(ExposedSettingsUnitOfWork(JdbcTransactionRunner(factory.database, 4), clock)).execute(user)
        execute(db, "UPDATE language_learning_user_setting SET origin_language='ko', learning_language='ja', feedback_language='ko' WHERE user_id=$user")
    }

    private fun scalar(db: LocalScratchMysql, statement: String): Long = db.connect().use { connection ->
        connection.createStatement().use { it.executeQuery(statement).use { rows -> check(rows.next()); rows.getLong(1) } }
    }

    private fun execute(db: LocalScratchMysql, statement: String) {
        db.connect().use { connection -> connection.createStatement().use { it.executeUpdate(statement) } }
    }
}
