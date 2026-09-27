package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingEvaluationAssets
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingEvaluationParser
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingEvaluationResult
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionCommand
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionPort
import jp.co.translacat.languagelearning.shared.ai.ModelMessage
import jp.co.translacat.languagelearning.shared.ai.ModelTier
import java.time.Instant

/** LL 소유 프롬프트/Schema/판정을 Python의 범용 단일 Provider 실행으로 보낸다. */
internal class WritingEvaluationExecution(private val model: ModelExecutionPort) {
    suspend fun evaluate(
        requestId: String,
        context: String,
        originLanguage: String,
        learningLanguage: String,
        compactRequestJson: String,
        deadlineUtc: Instant,
    ): WritingEvaluationResult {
        val prompt = WritingEvaluationAssets.prompt(context, originLanguage, learningLanguage, compactRequestJson)
        val result = model.execute(
            ModelExecutionCommand(
                traceId = requestId,
                instructions = WritingEvaluationAssets.instructions,
                messages = listOf(ModelMessage("user", prompt)),
                tier = ModelTier.MINI,
                maxOutputTokens = 8192,
                deadlineUtc = deadlineUtc,
                responseSchema = WritingEvaluationAssets.schema,
                schemaName = "LANGUAGE_LEARNING_WRITING_EVALUATION",
                strict = false,
                taskName = "LANGUAGE_LEARNING_WRITING_EVALUATION",
            ),
        )
        return WritingEvaluationParser.parse(result.output)
    }
}
