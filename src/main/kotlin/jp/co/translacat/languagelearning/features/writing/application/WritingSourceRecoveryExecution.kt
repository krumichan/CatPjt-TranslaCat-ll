package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingSourceRecovery
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionCommand
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionPort
import jp.co.translacat.languagelearning.shared.ai.ModelMessage
import jp.co.translacat.languagelearning.shared.ai.ModelTier
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** 원문 script만 잘못된 TRANSLATION 배치에 한 번 제안을 요청하며 승인하지는 않는다. */
internal class WritingSourceRecoveryExecution(private val model: ModelExecutionPort) {
    suspend fun propose(
        request: JsonObject,
        targetBand: Int,
        inputs: List<WritingSourceRecovery.Input>,
        batchId: String,
        deadlineUtc: Instant,
    ): List<WritingSourceRecovery.Proposal> {
        require(inputs.size in 1..2)
        val digest = WritingSourceRecovery.batchHash(inputs)

        // 원본 전체 hash와 후보 ID를 provider schema에 묶고 originText 제안만 받는다.
        val output = model.execute(
            ModelExecutionCommand(
                traceId = batchId,
                instructions = WritingSourceRecovery.instructions,
                messages = listOf(ModelMessage("user", WritingSourceRecovery.prompt(request, batchId, inputs))),
                tier = ModelTier.NANO,
                maxOutputTokens = 3072,
                deadlineUtc = deadlineUtc,
                responseSchema = WritingSourceRecovery.schema(request, targetBand, inputs),
                schemaName = "writing_source_localization",
                strict = true,
                taskName = "LANGUAGE_LEARNING_WRITING_SOURCE_LOCALIZATION",
            ),
        ).output

        // 통신 성공도 승인으로 취급하지 않는다. ID·hash·상태 교차 필드를 다시 검사한다.
        return WritingSourceRecovery.proposals(output, batchId, digest, inputs)
    }
}
