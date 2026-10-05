package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.features.growth.domain.repository.GrowthRepository
import jp.co.translacat.languagelearning.features.writing.domain.model.*
import jp.co.translacat.languagelearning.features.writing.domain.repository.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.*

class WritingReadMissingEvaluationTest {
    private val today = LocalDate.parse("2026-10-04")
    private val clock = Clock.fixed(Instant.parse("2026-10-04T01:00:00Z"), ZoneOffset.UTC)
    private val now = LocalDateTime.now(clock)
    private val set = WritingSet(17, 41, today, WritingType.FREE, "read-only", 1,
        WritingSetStatus.READY, "{}", "existing", 0, null, null, null)
    private val item = WritingItem(21, 17, 41, 1, WritingDifficulty.NORMAL, "Stored task", "[]", "[]",
        "Stored focus", null, null, null)

    @Test
    fun `평가 없는 오늘 답안은 원문과 null 상태를 보여 주고 재제출을 막는다`() = runBlocking {
        // 준비: 읽기 이외의 접근은 즉시 실패하여 새 평가나 저장을 만들 수 없게 한다.
        val service = WritingReadService(readOnlyWork(listOf(attempt(null))), clock)

        // 실행
        val detail = assertNotNull(service.byId(41, 17, today, 7))
        val stored = detail.getValue("items").jsonArray.single().jsonObject
        val answer = stored.getValue("attempts").jsonArray.single().jsonObject

        // 검증: 점수/상태를 만들어 내지 않고 기존 답안을 보존한다.
        assertEquals(JsonPrimitive("Stored answer"), answer["answer"])
        assertEquals(JsonNull, answer["evaluationStatus"])
        assertEquals(JsonNull, answer["evaluation"])
        assertEquals(JsonPrimitive("WRITING_EVALUATION_MISSING"), answer["evaluationFailureMessage"])
        assertEquals(JsonPrimitive(true), stored["answered"])
        assertEquals(JsonPrimitive(true), stored["answeredToday"])
        assertEquals(JsonPrimitive(false), stored["canSubmit"])
        assertEquals(detail, service.byDate(41, today, WritingType.FREE, today, 7))
        assertNull(service.byId(42, 17, today, 7))
    }

    @Test
    fun `기존 PENDING SUCCESS FAILED와 미제출의 제출 가능 계약을 보존한다`() = runBlocking {
        // 준비: 동일 조회 경계에서 각 기존 상태를 비교한다.
        val cases = listOf(
            WritingEvaluationStatus.PENDING to false,
            WritingEvaluationStatus.SUCCESS to false,
            WritingEvaluationStatus.FAILED to true,
        )
        for ((status, canSubmit) in cases) {
            // 실행
            val service = WritingReadService(readOnlyWork(listOf(attempt(status))), clock)
            val stored = assertNotNull(service.byId(41, 17, today, 7))
                .getValue("items").jsonArray.single().jsonObject

            // 검증: null을 FAILED로 바꾸거나 기존 FAILED 재시도를 막지 않는다.
            assertEquals(JsonPrimitive(canSubmit), stored["canSubmit"])
            assertEquals(JsonPrimitive(status.name), stored.getValue("attempts").jsonArray.single()
                .jsonObject["evaluationStatus"])
        }
        val empty = assertNotNull(WritingReadService(readOnlyWork(emptyList()), clock).byId(41, 17, today, 7))
            .getValue("items").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(true), empty["canSubmit"])
        assertEquals(JsonPrimitive(false), empty["answeredToday"])
    }

    @Test
    fun `지난 날짜의 평가 누락은 기존 복습 날짜와 만료 제한을 바꾸지 않는다`() = runBlocking {
        // 준비
        val service = WritingReadService(
            readOnlyWork(listOf(attempt(null, today.minusDays(1))), today.minusDays(1)), clock,
        )

        // 실행
        val available = assertNotNull(service.byId(41, 17, today, 7))
            .getValue("items").jsonArray.single().jsonObject
        val expired = assertNotNull(service.byId(41, 17, today.plusDays(7), 7))
            .getValue("items").jsonArray.single().jsonObject

        // 검증: 현재 답안이 없는 날짜는 기존 복습 정책을 따르고 만료 뒤에는 제출할 수 없다.
        assertEquals(JsonPrimitive(false), available["answeredToday"])
        assertEquals(JsonPrimitive(true), available["canSubmit"])
        assertEquals(JsonPrimitive(false), expired["canSubmit"])
    }

    private fun attempt(status: WritingEvaluationStatus?, date: LocalDate = today) = WritingAttemptView(
        WritingAnswerEvidence(31, item.id, set.userId, date, "Stored answer", status), now,
        if (status == null) "WRITING_EVALUATION_MISSING" else null,
        if (status == WritingEvaluationStatus.SUCCESS) WritingEvaluationView(
            1, "DAILY", 80, 90, 80, 70, 60, 50, "[]", "[]", "[]", "[]", "{}",
            "existing-rubric", "existing-policy", "existing-prompt", now,
        ) else null,
    )

    private fun readOnlyWork(attempts: List<WritingAttemptView>, learningDate: LocalDate = today): WritingSetUnitOfWork {
        val storedSet = set.copy(learningDate = learningDate)
        val setRepository = Proxy.newProxyInstance(WritingSetRepository::class.java.classLoader,
            arrayOf(WritingSetRepository::class.java)) { _, method, args ->
            check(method.name in setOf("find", "findById")) { "Unexpected set write: ${method.name}" }
            if (args[0] != storedSet.userId) null
            else if (method.name == "findById" && args[1] == storedSet.id) storedSet
            else if (method.name == "find" && args[1] == storedSet.learningDate && args[2] == storedSet.writingType) storedSet
            else null
        } as WritingSetRepository
        val itemRepository = Proxy.newProxyInstance(WritingItemRepository::class.java.classLoader,
            arrayOf(WritingItemRepository::class.java)) { _, method, args ->
            check(method.name == "list" && args[0] == set.userId && args[1] == set.id)
            listOf(item)
        } as WritingItemRepository
        val answerRepository = Proxy.newProxyInstance(WritingAnswerRepository::class.java.classLoader,
            arrayOf(WritingAnswerRepository::class.java)) { _, method, args ->
            check(method.name == "history" && args[0] == set.userId && args[1] == item.id)
            attempts
        } as WritingAnswerRepository
        val transaction = object : WritingSetTransaction {
            override val sets = setRepository
            override val items = itemRepository
            override val answers = answerRepository
            override val nowUtc = now
            override val evaluations: WritingEvaluationRepository get() = error("Unexpected evaluation access")
            override val fingerprints: WritingFingerprintRepository get() = error("Unexpected fingerprint access")
            override val growth: GrowthRepository get() = error("Unexpected growth access")
        }
        return object : WritingSetUnitOfWork {
            override suspend fun <T> read(block: WritingSetTransaction.() -> T): T = transaction.block()
            override suspend fun <T> write(userId: Long, block: WritingSetTransaction.() -> T): T = error("Read invoked write")
        }
    }
}
