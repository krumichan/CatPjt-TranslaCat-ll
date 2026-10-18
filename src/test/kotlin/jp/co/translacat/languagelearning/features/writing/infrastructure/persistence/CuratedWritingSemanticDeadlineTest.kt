package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.shared.ai.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.*

class CuratedWritingSemanticDeadlineTest {
    private val start = Instant.parse("2026-10-17T03:00:00Z")

    @Test
    fun `최초 관찰부터 동일한 240초 안에서 각 단계에 최대 90초만 배정한다`() = runBlocking {
        // 준비: 실제 대기 없이 모델 단계마다 흐른 단조 시간을 제어한다.
        var nanos = 0L
        val clock = Clock.fixed(start, ZoneOffset.UTC)
        val budget = CuratedWritingSemanticDeadline(clock) { nanos }
        val deadlines = mutableListOf<Instant>()
        val model = ModelExecutionPort { command ->
            deadlines += command.deadlineUtc
            ModelExecutionResult(buildJsonObject {}, 1, 1, "openai", "gpt-5-mini")
        }

        // 실행: 초기·독립 검사·교정·국소 복구가 하나의 전체 예산을 나눈다.
        listOf(0L, 80L, 170L, 235L).forEachIndexed { index, elapsed ->
            nanos = elapsed * 1_000_000_000L
            budget.execute(model, command(index))
        }

        // 검증: 세 번째와 네 번째에 새 90초를 추가하지 않는다.
        assertEquals(listOf(90L, 90L, 70L, 5L).map(start::plusSeconds), deadlines)
    }

    @Test
    fun `전체 예산이 소진되면 추가 모델 호출 없이 실패한다`() = runBlocking {
        // 준비: 앞 단계와 검증에 이미 전체 예산을 사용한 상태다.
        var nanos = 0L
        val budget = CuratedWritingSemanticDeadline(Clock.fixed(start, ZoneOffset.UTC)) { nanos }
        var calls = 0
        val model = ModelExecutionPort { calls++; error("소진 후 호출해서는 안 됩니다") }
        nanos = 240_000_000_000L

        // 실행·검증: deadline 계산 단계에서 중단하므로 새 과금 요청도 없다.
        val failure = assertFailsWith<ModelExecutionFailure> { budget.execute(model, command(4)) }
        assertEquals("WRITING_FEEDBACK_TOTAL_DEADLINE_EXCEEDED", failure.code)
        assertEquals(504, failure.status)
        assertFalse(failure.retryable)
        assertEquals(0, calls)
    }

    @Test
    fun `벽시계가 앞뒤로 바뀌어도 남은 전체 예산은 단조 경과 시간으로 정한다`() {
        // 준비: UTC는 전송용으로만 사용하고 전체 예산의 기준으로 삼지 않는다.
        var nanos = 0L
        val clock = MutableClock(start)
        val budget = CuratedWritingSemanticDeadline(clock) { nanos }

        // 실행·검증: 시각 보정 이후에도 남은 시간은 40초다.
        nanos = 200_000_000_000L
        clock.value = start.minusSeconds(3600)
        assertEquals(CuratedWritingSemanticDeadline.Allocation(clock.value.plusSeconds(40), 40_000), budget.allocate())
        clock.value = start.plusSeconds(3600)
        assertEquals(CuratedWritingSemanticDeadline.Allocation(clock.value.plusSeconds(40), 40_000), budget.allocate())
    }

    @Test
    fun `마지막 밀리초를 올림하여 전체 한도를 넘기지 않는다`() {
        // 준비: 호출·전송 시간 정밀도는 밀리초이므로 더 작은 잔여는 호출하지 않는다.
        var nanos = 0L
        val budget = CuratedWritingSemanticDeadline(Clock.fixed(start, ZoneOffset.UTC)) { nanos }

        // 실행·검증: 소수 밀리초는 버리고, 전송 가능한 시간이 없으면 새 요청을 중단한다.
        nanos = 239_998_100_000L
        assertEquals(1L, budget.allocate().timeoutMillis)
        nanos = 239_999_900_000L
        assertFailsWith<ModelExecutionFailure> { budget.allocate() }
    }

    @Test
    fun `상위 작업 시간 취소는 선택 제안 실패로 흡수하지 않고 전파한다`() = runBlocking {
        // 준비: 모델 자체 제한보다 짧은 외부 작업 제한을 둔다.
        val budget = CuratedWritingSemanticDeadline(Clock.fixed(start, ZoneOffset.UTC))
        var calls = 0
        val model = ModelExecutionPort { calls++; awaitCancellation() }

        // 실행 / 검증: 상위 취소는 ModelExecutionFailure로 바뀌지 않는다.
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(50) { budget.execute(model, command(0)) }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `모델 단계 자체 제한은 기존 불명 실패 계약으로 남긴다`() = runBlocking {
        // 준비: 외부 작업은 살아 있고 이 호출에만 50ms가 남아 있다.
        var nanos = 0L
        val budget = CuratedWritingSemanticDeadline(Clock.fixed(start, ZoneOffset.UTC)) { nanos }
        nanos = 239_950_000_000L
        var calls = 0
        val model = ModelExecutionPort { calls++; awaitCancellation() }

        // 실행 / 검증: 단계 timeout만 기존 코드로 반환하며 자동 재호출하지 않는다.
        val failure = assertFailsWith<ModelExecutionFailure> { budget.execute(model, command(0)) }
        assertEquals("MODEL_DEADLINE_EXCEEDED", failure.code)
        assertFalse(failure.retryable)
        assertEquals(1, calls)
    }

    private fun command(index: Int) = ModelExecutionCommand(
        traceId = "deadline-test-$index", instructions = "Observe only.",
        messages = listOf(ModelMessage("user", "Synthetic deadline test")), tier = ModelTier.MINI,
        maxOutputTokens = 4096, deadlineUtc = start.plusSeconds(999),
    )

    private class MutableClock(var value: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = value
    }
}
