package jp.co.translacat.languagelearning.features.speaking.execution

import jp.co.translacat.languagelearning.shared.ai.ModelExecutionFailure
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Instant

/** 원문이나 Provider 본문을 담지 않는 기존 Speaking 단계 실패다. */
internal class SpeakingStageFailure(
    val code: String,
    val stage: String,
    val retryable: Boolean,
) : RuntimeException(code)

/** 기술적 오류 출처를 기준으로 기존 Speaking provider_error 정책을 재현한다. */
internal fun speakingProviderFailure(
    failure: ModelExecutionFailure,
    stage: String,
    fallbackCode: String,
): SpeakingStageFailure {
    // 새 전송·DTO·프로토콜 실패는 기존 모델 품질 실패로 바꾸지 않는다.
    if (failure.code.startsWith("MODEL_") && failure.code != "MODEL_DEADLINE_EXCEEDED") throw failure
    if (failure.code.startsWith("EXECUTION_") && failure.code != "EXECUTION_SCHEMA_INVALID") throw failure
    val status = failure.providerStatus
    return when {
        status == 429 || failure.failureSignal == "RATE_LIMIT" ->
            SpeakingStageFailure("PROVIDER_RATE_LIMITED", stage, true)

        status in setOf(408, 504) || failure.failureSignal == "DEADLINE" ->
            SpeakingStageFailure("PROVIDER_TIMEOUT", stage, true)

        status in setOf(400, 401, 403) -> SpeakingStageFailure(fallbackCode, stage, false)
        failure.failureKind == "VALUE_ERROR" -> SpeakingStageFailure(
            if (stage in setOf("CONVERSATION", "EVALUATION")) "INVALID_RESPONSE_SCHEMA" else fallbackCode,
            stage, stage in setOf("CONVERSATION", "EVALUATION"),
        )

        failure.failureSignal == "SAFETY" -> SpeakingStageFailure("UNSAFE_TOPIC", stage, false)
        failure.code == "EXECUTION_SCHEMA_INVALID" -> SpeakingStageFailure("INVALID_RESPONSE_SCHEMA", stage, true)
        failure.code == "MODEL_DEADLINE_EXCEEDED" ||
            (failure.code == "PROVIDER_TIMEOUT" && failure.failureKind != "SDK_TIMEOUT") ->
            SpeakingStageFailure("PROVIDER_TIMEOUT", stage, true)

        else -> SpeakingStageFailure(fallbackCode, stage, true)
    }
}

internal suspend fun <T> speakingStageRetry(
    maxRetries: Int,
    stage: String,
    deadline: Instant,
    clock: Clock,
    operation: suspend () -> T,
): T {
    require(maxRetries in 0..2)
    // 기존 즉시 재시도 횟수와 하나의 전체 deadline을 함께 사용한다.
    for (attempt in 0..maxRetries) {
        if (!clock.instant().isBefore(deadline)) throw SpeakingStageFailure("PROVIDER_TIMEOUT", stage, true)
        try {
            return operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SpeakingStageFailure) {
            if (!failure.retryable || attempt == maxRetries) throw failure
        }
    }
    error("SPEAKING_RETRY_UNREACHABLE")
}
