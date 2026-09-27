package jp.co.translacat.languagelearning.features.speaking.application

import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingResultKind
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingCoachingExecution
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingEvaluationExecution
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingStageFailure
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionFailure
import jp.co.translacat.languagelearning.shared.schema.PydanticSchemaFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal data class SpeakingWorkerDiagnostic(val stage: String, val failureClass: String, val code: String)

internal class SpeakingEvaluationWorker(
    private val work: SpeakingUnitOfWork,
    private val state: SpeakingEvaluationState,
    private val results: SpeakingResultApplication,
    private val evaluation: SpeakingEvaluationExecution,
    private val coaching: SpeakingCoachingExecution,
    private val onFailure: (SpeakingWorkerDiagnostic) -> Unit,
) {
    suspend fun pending() = work.read { records.dueJobs(nowUtc, 20) }

    suspend fun process(userId: Long, sessionId: Long, problemIndex: Int) {
        val claim = state.claim(userId, sessionId, problemIndex) ?: return
        var stage = "MODEL_EXECUTION"
        try {
            // 저장된 immutable 요청으로 기존 평가 또는 코칭 실행만 호출한다.
            val response = if (claim.job.resultKind == SpeakingResultKind.SESSION_COACHING)
                coaching.coach(claim.job.request) else evaluation.evaluate(claim.job.request)
            stage = "RESULT_APPLY"
            results.complete(claim, response)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { state.release(claim) }
            throw cancelled
        } catch (failure: Exception) {
            // 기존 모델 실패·새 전송/DTO 오류·Core 교차 검증·DB 오류를 안전한 진단으로 구분한다.
            val diagnostic = when (failure) {
                is SpeakingStageFailure -> SpeakingWorkerDiagnostic(failure.stage, "MODEL_STAGE", failure.code)
                is ModelExecutionFailure -> SpeakingWorkerDiagnostic(stage, "EXECUTION_BOUNDARY", failure.code)
                is PydanticSchemaFailure -> SpeakingWorkerDiagnostic(
                    stage, "EXECUTION_CONTRACT", "SPEAKING_REQUEST_INVALID",
                )

                is IllegalArgumentException -> SpeakingWorkerDiagnostic(
                    stage,
                    if (stage == "RESULT_APPLY") "RESULT_VALIDATION" else "EXECUTION_CONTRACT",
                    "SPEAKING_RESULT_INVALID",
                )

                else -> SpeakingWorkerDiagnostic(
                    stage,
                    if (stage == "RESULT_APPLY") "RESULT_PERSISTENCE" else "EXECUTION_FAILURE",
                    failure.javaClass.simpleName,
                )
            }
            onFailure(diagnostic)
            try {
                state.fail(claim)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { state.release(claim) }
                throw cancelled
            } catch (persistenceFailure: Exception) {
                // 실패 상태도 저장되지 않으면 RUNNING lease를 남겨 기존 회수 경로가 다시 확인한다.
                onFailure(
                    SpeakingWorkerDiagnostic(
                        "FAILURE_PERSISTENCE", "RESULT_PERSISTENCE", persistenceFailure.javaClass.simpleName,
                    ),
                )
            }
        }
    }
}
