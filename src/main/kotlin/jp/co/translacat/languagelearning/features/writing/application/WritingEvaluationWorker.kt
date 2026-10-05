package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingEvaluationProtocolException
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionFailure
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/** pending lease를 단일 worker가 소유한다. 모델 대기 중 트랜잭션을 열지 않는다. */
internal class WritingEvaluationWorker(
    private val state: WritingEvaluationState,
    private val execution: WritingEvaluationExecution,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun process(
        userId: Long,
        answerId: Long,
        originLanguage: String,
        learningLanguage: String,
        learningDate: LocalDate,
    ): Boolean {
        val claim = state.claim(userId, answerId) ?: return false
        try {
            val context = state.context(claim, originLanguage, learningLanguage, learningDate)
            val overallDeadline = clock.instant().plus(Duration.ofSeconds(60))
            var lastFailure: RuntimeException? = null
            for (attempt in 1..2) {
                val remaining = Duration.between(clock.instant(), overallDeadline)
                if (remaining.isNegative || remaining.isZero) break
                val perCallDeadline = minOf(overallDeadline, clock.instant().plus(Duration.ofSeconds(30)))
                try {
                    val result = execution.evaluate(
                        context.requestId, "DAILY", context.originLanguage, context.learningLanguage,
                        context.compactRequestJson, perCallDeadline,
                    )
                    return state.publish(claim, result, context.learningDate, context.canonicalKeys)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: WritingEvaluationProtocolException) {
                    lastFailure = failure
                } catch (failure: ModelExecutionFailure) {
                    lastFailure = failure
                    // 원본 평가의 두 번 시도는 합법적 거부·SDK 설정 실패에도 동일하게 적용한다.
                    // 새 HTTP 계약·전송 오류는 기존 모델 응답 실패로 섞지 않는다.
                    if (failure.code !in legacyEvaluationFailures) break
                }
            }
            val code = when (val failure = lastFailure) {
                is WritingEvaluationProtocolException -> failure.code
                is ModelExecutionFailure -> failure.code
                else -> "WRITING_EVALUATION_DEADLINE_EXCEEDED"
            }
            state.fail(claim, code)
            return false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: WritingEvaluationContextException) {
            // 원본 언어가 불명인 과제는 모델 호출과 공식 성장 반영 전에 명시적 실패로 보존한다.
            state.fail(claim, failure.code)
            return false
        } catch (failure: RuntimeException) {
            state.fail(claim, "WRITING_EVALUATION_FAILED")
            throw failure
        }
    }

    private companion object {
        val legacyEvaluationFailures = setOf(
            "REFUSAL", "OUTPUT_TOKEN_LIMIT", "RESPONSE_INCOMPLETE", "EMPTY_OUTPUT", "JSON_INVALID",
            "PROVIDER_TIMEOUT", "PROVIDER_UNAVAILABLE", "PROVIDER_CONFIGURATION_ERROR",
            "PROVIDER_EXECUTION_FAILED", "EXECUTION_SCHEMA_INVALID", "MODEL_DEADLINE_EXCEEDED",
        )
    }
}
