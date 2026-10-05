package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingEvaluationAssets
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingEvaluationParser
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingEvaluationProtocolException
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingEvaluationResult
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionCommand
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionPort
import jp.co.translacat.languagelearning.shared.ai.ModelMessage
import jp.co.translacat.languagelearning.shared.ai.ModelTier
import org.slf4j.LoggerFactory
import java.time.Instant

/** LL 소유 프롬프트/Schema/판정을 Python의 범용 단일 Provider 실행으로 보낸다. */
internal class WritingEvaluationExecution(private val model: ModelExecutionPort) {
    private val logger = LoggerFactory.getLogger(WritingEvaluationExecution::class.java)
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
                strict = true,
                taskName = "LANGUAGE_LEARNING_WRITING_EVALUATION",
            ),
        )
        // 답변·Provider 출력·trace ID를 남기지 않고 정적인 검사 경로만 기록한다.
        return try {
            WritingEvaluationParser.parse(result.output)
        } catch (failure: WritingEvaluationProtocolException) {
            logger.warn("Writing evaluation schema rejected. field={}", failure.validationPath)
            throw failure
        }
    }
}
