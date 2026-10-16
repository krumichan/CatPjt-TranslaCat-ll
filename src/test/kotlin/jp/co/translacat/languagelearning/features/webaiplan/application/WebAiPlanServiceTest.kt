package jp.co.translacat.languagelearning.features.webaiplan.application

import jp.co.translacat.languagelearning.features.webaiplan.domain.WebAiPolicy
import jp.co.translacat.languagelearning.features.webaiplan.domain.WebAiSchedulePolicy
import jp.co.translacat.languagelearning.features.webaiplan.domain.model.*
import jp.co.translacat.languagelearning.features.webaiplan.support.WebAiMemoryStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.*
import java.util.UUID
import kotlin.test.*

class WebAiPlanServiceTest {
    @Test
    fun `언어 설정이 완료되지 않으면 계획을 생성하지 않는다`() = runBlocking {
        // 준비: 폼의 기본값이 아닌 저장된 유효 언어가 필요하다.
        val f = Fixture()
        f.current = f.current.copy(originLanguage = "")

        // 실행
        val failure = assertFailsWith<WebAiException> { f.service.save(1, null, f.change()) }

        // 검증
        assertEquals("WEB_AI_SETTINGS_REQUIRED", failure.code)
        assertEquals(409, failure.status)
        assertTrue(f.store.writes.isEmpty())
        assertEquals(0, f.calls)
    }

    @Test
    fun `카탈로그나 계획별 키워드만으로 공통 학습 키워드 선행 조건을 우회하지 못한다`() = runBlocking {
        // 준비: 카탈로그는 있어도 오늘 유효한 선택은 없는 사용자다.
        for (mode in listOf("DEFAULT", "CUSTOM")) {
            val f = Fixture()
            f.current = f.current.copy(defaultKeywords = emptyList())
            val change = f.change().copy(keywordMode = mode, keywords = listOf(f.current.catalog[1]))

            // 실행
            val failure = assertFailsWith<WebAiException> { f.service.save(1, null, change) }

            // 검증: 별도 키워드를 보낸 요청도 저장이나 평가를 시작하지 않는다.
            assertEquals("WEB_AI_KEYWORDS_REQUIRED", failure.code)
            assertEquals(409, failure.status)
            assertTrue(f.store.writes.isEmpty())
            assertEquals(0, f.calls)
        }
    }

    @Test
    fun `유효 키워드가 없어지면 수정과 새 연결 및 출제를 막고 기존 토큰과 실행은 보존한다`() = runBlocking {
        // 준비: 유효한 설정으로 만든 계획과 실행은 이미 존재한다.
        val f = Fixture()
        val plan = f.plan()
        val token = f.service.connection(1, plan.id)
        val run = f.service.run(1, plan.id, "existing")
        f.current = f.current.copy(defaultKeywords = emptyList())
        val previousWrites = f.store.writes.size

        // 실행
        val update = assertFailsWith<WebAiException> {
            f.service.save(1, plan.id, f.change().copy(revision = plan.revision, name = "changed"))
        }
        val connection = assertFailsWith<WebAiException> { f.service.connection(1, plan.id) }
        val manual = assertFailsWith<WebAiException> { f.service.run(1, plan.id, "new") }
        val external = assertFailsWith<WebAiException> { f.service.externalRun(token, "scheduled") }

        // 검증: 새 작업을 막아도 기존 학습 데이터나 연결 키를 삭제하지 않는다.
        listOf(update, connection, manual, external).forEach {
            assertEquals("WEB_AI_KEYWORDS_REQUIRED", it.code)
            assertEquals(409, it.status)
        }
        assertEquals(previousWrites, f.store.writes.size)
        assertEquals(plan, f.service.plans(1).single())
        assertEquals(run, f.service.runById(1, run.id))
        assertNotNull(f.store.capability(WebAiPolicy.sha256(token)))

        // 실행 / 검증: 공통 설정이 준비되면 같은 계획으로 다시 출제할 수 있다.
        f.current = f.current.copy(defaultKeywords = listOf(f.current.catalog[0]))
        assertEquals(plan.id, f.service.externalRun(token, "restored").planId)
    }

    @Test
    fun `계획별 선택 모드에는 키워드가 필요하고 과거의 빈 계획도 새 출제를 막는다`() = runBlocking {
        // 준비: 공통 설정은 준비됐지만 계획별 선택이 비어 있다.
        val f = Fixture()
        val custom = f.change().copy(keywordMode = "CUSTOM", keywords = emptyList())

        // 실행 / 검증: 공통 키워드 미설정과 계획 입력 누락을 구분한다.
        val create = assertFailsWith<WebAiException> { f.service.save(1, null, custom) }
        assertEquals("WEB_AI_PLAN_KEYWORDS_REQUIRED", create.code)
        assertEquals(409, create.status)
        assertTrue(f.store.writes.isEmpty())

        // 준비: 이전 버전에서 저장할 수 있었던 빈 선택 계획을 재현한다.
        val legacy = f.plan().copy(keywordMode = "CUSTOM", keywords = emptyList())
        f.store.write(1) { savePlan(legacy) }

        // 실행 / 검증: 기존 계획은 남기고 새 연결과 출제를 거부한다.
        val connection = assertFailsWith<WebAiException> { f.service.connection(1, legacy.id) }
        val run = assertFailsWith<WebAiException> { f.service.run(1, legacy.id, "legacy") }
        assertEquals("WEB_AI_PLAN_KEYWORDS_REQUIRED", connection.code)
        assertEquals("WEB_AI_PLAN_KEYWORDS_REQUIRED", run.code)
        assertTrue(f.service.runs(1, 20).isEmpty())
        assertEquals(legacy, f.service.plans(1).single())
        assertEquals(0, f.calls)
    }

    @Test
    fun `키워드가 없어져도 이전 답안 제출 검증 이력 조회 삭제와 연결 해제는 가능하다`() = runBlocking {
        // 준비
        for (asynchronous in listOf(false, true)) {
            val f = Fixture(asynchronous)
            val plan = f.plan()
            val token = f.service.connection(1, plan.id)
            val run = f.service.run(1, plan.id, "existing")
            f.current = f.current.copy(defaultKeywords = emptyList())

            // 실행: 이미 배정된 문제의 답안은 설정 변경 이후에도 제출·평가한다.
            val accepted = f.service.submit(1, f.submission(run))
            val result = if (asynchronous) f.service.processQueued(1, accepted.id) else accepted
            f.service.revoke(1, plan.id)
            f.service.delete(1, plan.id)

            // 검증
            assertEquals("VERIFIED", result.status)
            assertEquals(result, f.service.result(1, result.id))
            assertEquals(run, f.service.runById(1, run.id))
            assertEquals(1, f.store.growthApplications.size)
            assertTrue(f.service.plans(1).isEmpty())
            assertNull(f.store.capability(WebAiPolicy.sha256(token)))
        }
    }

    @Test
    fun `공통 언어를 상속하고 계획별 키워드는 서버 카탈로그로 다시 해석한다`() = runBlocking {
        // 준비
        val f = Fixture()
        val custom = WebAiKeyword("SYSTEM:2", "stale client text", "forged mastery")

        // 실행
        val plan = f.service.save(1, null, f.change().copy(keywordMode = "CUSTOM", keywords = listOf(custom)))
        f.current = f.current.copy(feedbackLanguage = "en")
        val visible = f.service.plans(1).single()
        val run = f.service.run(1, plan.id, "manual-1")

        // 검증
        assertEquals("ko", visible.originLanguage)
        assertEquals("ja", visible.learningLanguage)
        assertEquals("en", visible.feedbackLanguage)
        assertEquals(listOf(f.current.catalog[1]), run.keywords)
        assertEquals(listOf(f.current.catalog[0]), f.current.defaultKeywords)
    }

    @Test
    fun `생성 재전송은 기존 계획을 돌려주고 오래된 revision은 거부한다`() = runBlocking {
        // 준비
        val f = Fixture()
        val change = f.change()

        // 실행
        val first = f.service.save(1, null, change)
        val repeated = f.service.save(1, null, change)
        val updated = f.service.save(1, first.id, change.copy(name = "updated", revision = 1))
        val failure = assertFailsWith<WebAiException> {
            f.service.save(1, first.id, change.copy(name = "stale", revision = 1))
        }

        // 검증
        assertEquals(first, repeated)
        assertEquals(2, updated.revision)
        assertEquals(409, failure.status)
        assertEquals("updated", f.service.plans(1).single().name)
    }

    @Test
    fun `큰 배정 수를 그대로 보존하되 회차마다 모든 문항 객체를 만들지 않는다`() = runBlocking {
        // 준비
        val f = Fixture()
        val change = f.change().copy(areas = listOf(WebAiArea("WRITING_TRANSLATION", 1_000_000, 0, 0)))

        // 실행
        val plan = f.service.save(1, null, change)
        val run = f.service.run(1, plan.id, "large")
        val submitted = f.service.submit(1, f.submission(run, listOf(f.item(1_000_000))))

        // 검증
        assertEquals(1_000_000, run.areas.single().easy)
        assertTrue(run.prompt.length < 20_000)
        assertEquals("VERIFIED", submitted.status)
    }

    @Test
    fun `상위 전체 분야와 배정 범위를 벗어난 문항은 평가 전에 거부한다`() = runBlocking {
        // 준비
        val f = Fixture()

        // 실행
        assertFailsWith<WebAiException> { f.service.save(1, null, f.change().copy(areas = listOf(WebAiArea("WRITING", 1, 0, 0)))) }
        val run = f.run()
        val invalid = f.item(1).copy(itemId = "WRITING_TRANSLATION:HARD:1", difficulty = "HARD")
        assertFailsWith<WebAiException> { f.service.submit(1, f.submission(run, listOf(invalid))) }

        // 검증
        assertEquals(0, f.calls)
        assertTrue(f.service.results(1, 20).isEmpty())
    }

    @Test
    fun `연결 키 교체 폐기와 소유권을 검사한다`() = runBlocking {
        // 준비
        val f = Fixture()
        val plan = f.plan()
        val old = f.service.connection(1, plan.id)

        // 실행
        val active = f.service.connection(1, plan.id)
        assertFailsWith<WebAiException> { f.service.externalRun(old, "attempt") }
        val run = f.service.externalRun(active, "first")
        val sameOccurrence = f.service.externalRun(active, "different-random-key")
        assertFailsWith<WebAiException> { f.service.runById(2, run.id) }
        f.service.revoke(1, plan.id)
        val failure = assertFailsWith<WebAiException> { f.service.externalRun(active, "again") }

        // 검증
        assertEquals(run.id, sameOccurrence.id)
        assertEquals(401, failure.status)
        assertTrue(f.service.runs(2, 20).isEmpty())
    }

    @Test
    fun `다른 계획의 연결 키로 결과를 제출할 수 없다`() = runBlocking {
        // 준비
        val f = Fixture()
        val first = f.plan()
        val second = f.plan()
        val token = f.service.connection(1, first.id)
        val run = f.service.run(1, second.id, "manual")

        // 실행
        val failure = assertFailsWith<WebAiException> { f.service.externalSubmit(token, f.submission(run)) }

        // 검증
        assertEquals(401, failure.status)
        assertEquals(0, f.calls)
    }

    @Test
    fun `일시 정지와 삭제는 새 실행을 막고 이전 이력은 보존한다`() = runBlocking {
        // 준비
        val f = Fixture()
        val plan = f.plan()
        val token = f.service.connection(1, plan.id)
        val run = f.service.run(1, plan.id, "manual")
        val result = f.service.submit(1, f.submission(run))

        // 실행
        f.service.save(1, plan.id, f.change().copy(id = plan.id, revision = plan.revision, enabled = false))
        assertFailsWith<WebAiException> { f.service.externalRun(token, "paused") }
        f.service.delete(1, plan.id)
        assertFailsWith<WebAiException> { f.service.externalRun(token, "deleted") }

        // 검증
        assertTrue(f.service.plans(1).isEmpty())
        assertEquals(result, f.service.result(1, result.id))
        assertEquals(run, f.service.runById(1, run.id))
    }

    @Test
    fun `원문은 그대로 보존하고 외부 점수 대신 내부 점수를 한 번 합산한다`() = runBlocking {
        // 준비
        val f = Fixture()
        val run = f.run()
        val body = f.submission(run).copy(originalFeedbackMarkdown = "# 내 피드백\n\n| 작성 | 교정 |\n|---|---|\n|원문|교정문|\n")

        // 실행
        val first = f.service.submit(1, body)
        val duplicate = f.service.submit(1, body)
        val changed = assertFailsWith<WebAiException> { f.service.submit(1, body.copy(originalFeedbackMarkdown = "changed")) }

        // 검증
        assertEquals(body.originalFeedbackMarkdown, first.originalFeedbackMarkdown)
        assertEquals(99.0, first.items.single().item.externalScore)
        assertEquals(74.0, first.items.single().officialScore)
        assertEquals(first, duplicate)
        assertEquals(409, changed.status)
        assertEquals(1, f.calls)
        assertEquals(1, f.store.growthApplications.size)
    }

    @Test
    fun `부분 제출을 이어 받을 수 있지만 다른 배치로 동일 문항을 중복 합산하지 않는다`() = runBlocking {
        // 준비
        val f = Fixture()
        val run = f.run()

        // 실행
        f.service.submit(1, f.submission(run, listOf(f.item(1))))
        f.service.submit(1, f.submission(run, listOf(f.item(2))))
        assertFailsWith<WebAiException> { f.service.submit(1, f.submission(run, listOf(f.item(1)))) }

        // 검증
        assertEquals(2, f.calls)
        assertEquals(2, f.store.growthApplications.size)
        assertEquals(2, f.service.results(1, 20).size)
    }

    @Test
    fun `실패한 문항만 다시 평가하고 성공 문항의 점수는 보존한다`() = runBlocking {
        // 준비
        val f = Fixture()
        f.failing = true
        f.failureOrdinal = 2
        val run = f.run()

        // 실행
        val partial = f.service.submit(1, f.submission(run, listOf(f.item(1), f.item(2))))
        f.failing = false
        val finished = f.service.retry(1, partial.id)

        // 검증
        assertEquals("PARTIAL", partial.status)
        assertEquals(listOf("VERIFIED", "FAILED"), partial.items.map { it.status })
        assertEquals("VERIFIED", finished.status)
        assertEquals(3, f.calls)
        assertEquals(2, f.store.growthApplications.size)
    }

    @Test
    fun `계속 실패해도 자동 재호출하지 않고 두 번의 명시적 재시도까지만 허용한다`() = runBlocking {
        // 준비
        val f = Fixture()
        f.failing = true
        val run = f.run()

        // 실행
        val failed = f.service.submit(1, f.submission(run))
        f.service.retry(1, failed.id)
        f.service.retry(1, failed.id)
        val failure = assertFailsWith<WebAiException> { f.service.retry(1, failed.id) }

        // 검증
        assertEquals("FAILED", failed.status)
        assertEquals("WEB_AI_RETRY_LIMIT", failure.code)
        assertEquals(3, f.calls)
        assertTrue(f.store.growthApplications.isEmpty())
    }

    @Test
    fun `평가 중 언어가 바뀌면 답안을 보존하고 늦은 점수 반영을 거부한다`() = runBlocking {
        // 준비
        val f = Fixture()
        val run = f.run()
        f.onVerify = {
            f.current = f.current.copy(learningLanguage = "en")
            f.store.learningLanguage = "en"
        }

        // 실행
        val failure = assertFailsWith<WebAiException> { f.service.submit(1, f.submission(run)) }

        // 검증
        assertEquals("WEB_AI_LANGUAGE_CHANGED", failure.code)
        assertTrue(f.store.growthApplications.isEmpty())
        assertEquals("REJECTED", f.service.results(1, 20).single().status)
        assertEquals("学習者の回答", f.service.results(1, 20).single().items.single().item.answerText)
    }

    @Test
    fun `같은 사용자의 병렬 검증은 거부하고 동일 제출의 조회는 재평가하지 않는다`() = runBlocking {
        // 준비
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onVerify = { entered.complete(Unit); release.await() }
        val run = f.run()
        val body = f.submission(run)

        // 실행
        val first = async { f.service.submit(1, body) }
        entered.await()
        val duplicate = f.service.submit(1, body)
        val busy = assertFailsWith<WebAiException> { f.service.submit(1, f.submission(run, listOf(f.item(2)))) }
        release.complete(Unit)
        first.await()

        // 검증
        assertEquals("VERIFYING", duplicate.status)
        assertEquals(429, busy.status)
        assertEquals(1, f.calls)
        assertEquals(1, f.store.growthApplications.size)
    }

    @Test
    fun `취소된 검증은 lease 만료 뒤 회복하며 기존 답안은 바뀌지 않는다`() = runBlocking {
        // 준비
        val f = Fixture()
        val run = f.run()
        f.onVerify = { throw CancellationException("synthetic disconnect") }

        // 실행
        assertFailsWith<CancellationException> { f.service.submit(1, f.submission(run)) }
        val pending = f.service.results(1, 20).single()
        assertFailsWith<WebAiException> { f.service.retry(1, pending.id) }
        f.clock.now = f.clock.now.plusSeconds(301)
        f.onVerify = {}
        val recovered = f.service.retry(1, pending.id)

        // 검증
        assertEquals("VERIFIED", recovered.status)
        assertEquals(pending.items.single().item, recovered.items.single().item)
        assertEquals(1, f.store.growthApplications.size)
    }

    @Test
    fun `음성 근거가 없는 코칭 결과는 원문만 보존하고 공식 점수에 넣지 않는다`() = runBlocking {
        // 준비
        val f = Fixture()
        val plan = f.plan(listOf(WebAiArea("SPEAKING_FREE", 1, 0, 0)))
        val run = f.service.run(1, plan.id, "audio")
        f.coaching = true
        val item = f.item(1).copy(itemId = "SPEAKING_FREE:EASY:1", areaCode = "SPEAKING_FREE")

        // 실행
        val result = f.service.submit(1, f.submission(run, listOf(item)))

        // 검증
        assertEquals("COACHING", result.items.single().status)
        assertNull(result.items.single().officialScore)
        assertTrue(f.store.growthApplications.isEmpty())
        assertTrue(result.originalFeedbackMarkdown.isNotBlank())
    }

    @Test
    fun `월별 없는 일자를 건너뛰고 월말 중복은 같은 회차가 된다`() = runBlocking {
        // 준비
        val f = Fixture()
        val plan = f.service.save(1, null, f.change().copy(recurrence = "MONTHLY", monthDays = listOf(31), includeLastDay = false))

        // 실행
        val skipped = WebAiSchedulePolicy.latestOccurrence(plan, Instant.parse("2026-03-01T00:00:00Z"))
        val monthEnd = WebAiSchedulePolicy.latestOccurrence(plan.copy(includeLastDay = true), Instant.parse("2026-03-01T00:00:00Z"))

        // 검증
        assertEquals(LocalDate.parse("2026-01-31"), skipped.toLocalDate())
        assertEquals(LocalDate.parse("2026-02-28"), monthEnd.toLocalDate())
        assertEquals("18:00", monthEnd.toLocalTime().toString())
    }

    @Test
    fun `비동기 제출은 원문을 먼저 확정하고 작업자가 독립적으로 검증한다`() = runBlocking {
        // 준비
        val f = Fixture(asynchronous = true)
        val run = f.run()
        val body = f.submission(run)

        // 실행
        val accepted = f.service.submit(1, body)
        assertEquals(0, f.calls)
        val duplicate = f.service.submit(1, body)
        val processed = f.service.processQueued(1, accepted.id)
        val repeatedWorker = f.service.processQueued(1, accepted.id)

        // 검증
        assertEquals("PENDING", accepted.status)
        assertEquals(accepted, duplicate)
        assertEquals(body.originalFeedbackMarkdown, accepted.originalFeedbackMarkdown)
        assertEquals("VERIFIED", processed.status)
        assertEquals(processed, repeatedWorker)
        assertEquals(1, f.calls)
        assertEquals(1, f.store.growthApplications.size)
    }

    @Test
    fun `비동기 실패 재시도는 대기열에만 넣고 명시한 작업자가 실패 문항만 처리한다`() = runBlocking {
        // 준비
        val f = Fixture(asynchronous = true)
        f.failing = true
        val run = f.run()
        val accepted = f.service.submit(1, f.submission(run))
        f.service.processQueued(1, accepted.id)

        // 실행
        val queued = f.service.retry(1, accepted.id)
        assertEquals(1, f.calls)
        f.failing = false
        val verified = f.service.processQueued(1, queued.id)

        // 검증
        assertEquals("PENDING", queued.status)
        assertEquals("VERIFIED", verified.status)
        assertEquals(2, f.calls)
        assertEquals(1, f.store.growthApplications.size)
    }

    @Test
    fun `관리자가 AI 평가를 중지하면 원문은 저장하되 Provider는 호출하지 않는다`() = runBlocking {
        // 준비
        val f = Fixture(asynchronous = true)
        f.current = f.current.copy(evaluationEnabled = false)
        val run = f.run()

        // 실행
        val accepted = f.service.submit(1, f.submission(run))
        val disabled = f.service.processQueued(1, accepted.id)
        val failure = assertFailsWith<WebAiException> { f.service.retry(1, disabled.id) }

        // 검증
        assertEquals("FAILED", disabled.status)
        assertEquals("AI_EVALUATION_DISABLED", disabled.items.single().reason)
        assertEquals("WEB_AI_EVALUATION_DISABLED", failure.code)
        assertEquals(0, f.calls)
        assertTrue(f.store.growthApplications.isEmpty())
    }

    @Test
    fun `대기 중 언어가 바뀌면 재호출 없이 원문과 거절 사유를 보존한다`() = runBlocking {
        // 준비
        val f = Fixture(asynchronous = true)
        val accepted = f.service.submit(1, f.submission(f.run()))
        f.current = f.current.copy(learningLanguage = "en")
        f.store.learningLanguage = "en"

        // 실행
        val rejected = f.service.processQueued(1, accepted.id)

        // 검증
        assertEquals("REJECTED", rejected.status)
        assertEquals("WEB_AI_LANGUAGE_CHANGED", rejected.items.single().reason)
        assertEquals(accepted.originalFeedbackMarkdown, rejected.originalFeedbackMarkdown)
        assertEquals(0, f.calls)
    }

    @Test
    fun `마지막 허용 작업도 중단되면 더 호출하지 않고 실패로 종결한다`() = runBlocking {
        // 준비
        val f = Fixture(asynchronous = true)
        val accepted = f.service.submit(1, f.submission(f.run()))
        f.onVerify = { throw CancellationException("synthetic restart") }

        // 실행
        repeat(3) {
            assertFailsWith<CancellationException> { f.service.processQueued(1, accepted.id) }
            f.clock.now = f.clock.now.plusSeconds(301)
        }
        val ended = f.service.processQueued(1, accepted.id)

        // 검증
        assertEquals("FAILED", ended.status)
        assertEquals("WEB_AI_RETRY_LIMIT", ended.items.single().reason)
        assertEquals(3, f.calls)
        assertTrue(f.store.growthApplications.isEmpty())
    }

    private class Fixture(asynchronous: Boolean = false) {
        val store = WebAiMemoryStore()
        val clock = MutableClock()
        var current = WebAiContext("ko", "ja", "ko", listOf(WebAiKeyword("SYSTEM:1", "요건정의", "requirements")),
            listOf(WebAiKeyword("SYSTEM:1", "요건정의", "requirements"), WebAiKeyword("SYSTEM:2", "고객 회의", "meeting")))
        var calls = 0
        var failing = false
        var failureOrdinal: Int? = null
        var coaching = false
        var onVerify: suspend () -> Unit = {}
        val service = WebAiPlanService(store, { current }, { input ->
            calls += 1
            onVerify()
            if (failing && (failureOrdinal == null || input.item.itemId.endsWith(":$failureOrdinal"))) error("synthetic provider failure")
            if (coaching) WebAiVerifiedItem(input.item, "COACHING", reason = "AUDIO_EVIDENCE_REQUIRED") else
                WebAiVerifiedItem(input.item, "VERIFIED", 74.0, buildJsonObject { put("summary", "의미는 전달됐지만 더 자연스럽게 다듬을 수 있어요.") },
                    listOf(WebAiMetric("MEANING", 74.0)), List(5) { 74.0 })
        }, clock, asynchronous)

        fun change() = WebAiPlanChange(UUID.randomUUID().toString(), name = "실무 일본어", timezone = "Asia/Tokyo", time = "18:00",
            recurrence = "DAILY", areas = listOf(WebAiArea("WRITING_TRANSLATION", 5, 0, 0)))
        suspend fun plan(areas: List<WebAiArea>? = null): WebAiPlan = service.save(1, null, change().let { if (areas == null) it else it.copy(areas = areas) })
        suspend fun run(): WebAiRun = service.run(1, plan().id, "manual-${UUID.randomUUID()}")
        fun item(ordinal: Int) = WebAiSubmittedItem("WRITING_TRANSLATION:EASY:$ordinal", "WRITING_TRANSLATION", "EASY",
            "고객에게 자료 확인을 요청하세요.", "学習者の回答", sourceText = "자료를 확인해 주세요.", externalScore = 99.0)
        fun submission(run: WebAiRun, items: List<WebAiSubmittedItem> = listOf(item(1))) = WebAiResultSubmission(
            run.id, UUID.randomUUID().toString(), "# 원문 피드백\n\n표현을 구체적으로 설명해요.", items,
        )
    }

    private class MutableClock(var now: Instant = Instant.parse("2026-10-15T10:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }
}
