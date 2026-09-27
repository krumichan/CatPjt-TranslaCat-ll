package jp.co.translacat.languagelearning.features.listening.domain.policy

import jp.co.translacat.languagelearning.features.listening.application.ListeningExecutionBudget
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionFailure
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class ListeningExecutionBudgetTest {
    @Test
    fun `호출별 예산은 전체 deadline을 늘리지 않고 stage timeout만 재시도한다`() = runTest {
        // 준비
        val now = Instant.parse("2026-09-26T05:00:00Z")
        val budget = ListeningExecutionBudget(Clock.fixed(now, ZoneOffset.UTC))
        val deadlines = mutableListOf<Instant>()

        // 실행
        budget.call(now.plusSeconds(180), 60) { deadlines += it }
        budget.call(now.plusSeconds(20), 60) { deadlines += it }
        val stage = assertFailsWith<ModelExecutionFailure> {
            budget.call(now.plusSeconds(180), 60) { throw ModelExecutionFailure("MODEL_DEADLINE_EXCEEDED", 504, false) }
        }
        val overall = assertFailsWith<ModelExecutionFailure> {
            budget.call(now.plusSeconds(20), 60) { throw ModelExecutionFailure("MODEL_DEADLINE_EXCEEDED", 504, false) }
        }

        // 검증
        assertEquals(listOf(now.plusSeconds(60), now.plusSeconds(20)), deadlines)
        assertEquals("PROVIDER_TIMEOUT", stage.code)
        assertTrue(stage.retryable)
        assertEquals("MODEL_DEADLINE_EXCEEDED", overall.code)
        assertFalse(overall.retryable)
    }
}
