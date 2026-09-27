package jp.co.translacat.languagelearning.features.listening.application

import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningEvaluationContext
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskResult
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskType
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningAssets
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningProtocolFailure
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningScoring
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningSemanticEvaluation
import jp.co.translacat.languagelearning.shared.ai.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

internal class ListeningEvaluationExecution(
    private val model: ModelExecutionPort,
    private val timeoutSeconds: Long = 60,
    private val budget: ListeningExecutionBudget = ListeningExecutionBudget(),
) {
    suspend fun evaluate(
        task: ListeningTaskType, request: JsonObject, context: ListeningEvaluationContext,
        deadline: Instant, automaticRetryLimit: Int = 2,
    ): ListeningTaskResult {
        require(automaticRetryLimit in 0..2)
        if (context.revealed) return ListeningScoring.revealed(task, context)
        val name = when (task) {
            ListeningTaskType.INTERPRETATION -> "interpretation"
            ListeningTaskType.SUMMARY -> "summary"
            else -> error("의미 평가 task가 아닙니다.")
        }
        val trace = request.getValue("requestId").jsonPrimitive.content
        val units = (request["keyMeaningUnits"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }

        // 요청 전체 deadline을 재사용하고 기존 기술 오류 재시도 상한만 적용한다.
        var attempt = 0
        while (true) {
            try {
                val result = budget.call(deadline, timeoutSeconds) { callDeadline ->
                    model.execute(
                        ModelExecutionCommand(
                            trace, ListeningAssets.instructions(name),
                            listOf(ModelMessage("user", ListeningSemanticEvaluation.prompt(task, request))),
                            ModelTier.MINI, 8192,
                            callDeadline, ListeningAssets.schema(name), "Listening${task.name}",
                            taskName = "LANGUAGE_LEARNING_LISTENING_${if (task == ListeningTaskType.SUMMARY) "SUMMARY_EVALUATION" else "INTERPRETATION"}",
                        ),
                    )
                }
                return ListeningSemanticEvaluation.parse(task, result.output, units, context)
            } catch (failure: ModelExecutionFailure) {
                if (!failure.retryable || attempt >= automaticRetryLimit || !Instant.now()
                        .isBefore(deadline)
                ) throw failure
                attempt++
            } catch (failure: ListeningProtocolFailure) {
                // 기존 Summary만 schema 오류를 재시도하며 Interpretation 프로토콜 오류는 즉시 실패한다.
                if (task != ListeningTaskType.SUMMARY || attempt >= automaticRetryLimit || !Instant.now()
                        .isBefore(deadline)
                ) throw failure
                attempt++
            }
        }
    }
}
