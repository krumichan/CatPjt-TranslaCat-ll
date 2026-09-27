package jp.co.translacat.languagelearning.features.listening.application

import jp.co.translacat.languagelearning.shared.ai.ModelExecutionFailure
import java.time.Clock
import java.time.Instant

/** 기존 Python의 호출별 제한과 상위 업무의 전체 deadline을 동시에 적용한다. */
internal class ListeningExecutionBudget(private val clock: Clock = Clock.systemUTC()) {
    suspend fun <T> call(overallDeadline: Instant, seconds: Long, operation: suspend (Instant) -> T): T {
        require(seconds > 0)
        val callDeadline = minOf(overallDeadline, clock.instant().plusSeconds(seconds))
        try {
            return operation(callDeadline)
        } catch (failure: ModelExecutionFailure) {
            // 기존 stage timeout은 남은 전체 시간이 있을 때만 기존 재시도 대상으로 유지한다.
            if (failure.code in setOf("MODEL_DEADLINE_EXCEEDED", "SPEECH_DEADLINE_EXCEEDED") &&
                callDeadline < overallDeadline && clock.instant() < overallDeadline
            ) {
                throw ModelExecutionFailure("PROVIDER_TIMEOUT", 504, true)
            }
            throw failure
        }
    }
}
