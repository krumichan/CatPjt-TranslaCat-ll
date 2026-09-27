package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.*
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionCommand
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionPort
import jp.co.translacat.languagelearning.shared.ai.ModelMessage
import jp.co.translacat.languagelearning.shared.ai.ModelTier
import kotlinx.serialization.json.*
import java.time.Instant

/** 기존 Luna 생성 한 번을 실행한다. 슬롯 재시도·보정·게시 예산은 상위 worker가 관리한다. */
internal class WritingGenerationExecution(private val model: ModelExecutionPort) {
    internal data class Batch(val drafts: List<WritingCandidateDraft>, val rejections: Map<String, Int>)

    suspend fun generate(
        request: JsonObject,
        writingType: WritingType,
        targetBand: Int,
        attempt: Int,
        deadlineUtc: Instant,
        feedback: Map<String, Int> = emptyMap(),
        sourceRecoveryMode: Boolean = false,
        difficultyRecovery: JsonObject? = null,
    ): Batch {
        val prompt = WritingGenerationPrompt.build(
            request, writingType, targetBand, attempt, feedback,
            sourceRecoveryMode, difficultyRecovery,
        )
        val instructions = requireNotNull(javaClass.getResource("/writing/generation-system-prompt.txt"))
            .readText().replace("\r\n", "\n").trimEnd()
        val result = model.execute(
            ModelExecutionCommand(
                traceId = request.getValue("requestId").jsonPrimitive.content,
                instructions = instructions,
                messages = listOf(ModelMessage("user", prompt)),
                tier = ModelTier.LUNA,
                maxOutputTokens = 8192,
                deadlineUtc = deadlineUtc,
                responseSchema = WritingGenerationSchema.build(
                    request, writingType, targetBand,
                    request.getValue("originLanguage").jsonPrimitive.content,
                ),
                schemaName = "writing_candidate_batch",
                strict = true,
                taskName = "LANGUAGE_LEARNING_DAILY_WRITING_GENERATION",
            ),
        )
        return parseBatch(result.output, 2)
    }

    suspend fun repair(
        request: JsonObject,
        plan: WritingDifficultyRepairPlan,
        deadlineUtc: Instant,
    ): Batch {
        val instructions = requireNotNull(javaClass.getResource("/writing/generation-system-prompt.txt"))
            .readText().replace("\r\n", "\n").trimEnd()
        val result = model.execute(
            ModelExecutionCommand(
                traceId = request.getValue("requestId").jsonPrimitive.content,
                instructions = instructions,
                messages = listOf(ModelMessage("user", plan.generationPrompt(request))),
                tier = ModelTier.LUNA,
                maxOutputTokens = 8192,
                deadlineUtc = deadlineUtc,
                responseSchema = plan.generationSchema(request),
                schemaName = "writing_candidate_batch",
                strict = true,
                taskName = "LANGUAGE_LEARNING_DAILY_WRITING_GENERATION",
            ),
        )
        val batch = parseBatch(result.output, 1)
        val valid = mutableListOf<WritingCandidateDraft>()
        val rejected = batch.rejections.toMutableMap()
        for (draft in batch.drafts) {
            val reason = plan.changeReason(request, draft)
            if (reason == null) valid += draft else rejected[reason] = (rejected[reason] ?: 0) + 1
        }
        return Batch(valid, rejected)
    }

    private fun parseBatch(output: JsonElement, candidateLimit: Int): Batch {
        val raw = output as? JsonObject ?: return Batch(emptyList(), mapOf("GENERATOR_BATCH_SCHEMA" to 1))
        if (raw.keys != setOf("items") || raw["items"] !is JsonArray)
            return Batch(emptyList(), mapOf("GENERATOR_BATCH_SCHEMA" to 1))
        val items = raw.getValue("items").jsonArray
        if (items.size !in 1..candidateLimit) return Batch(emptyList(), mapOf("GENERATOR_CANDIDATE_COUNT" to 1))
        val drafts = mutableListOf<WritingCandidateDraft>()
        var invalid = 0
        for (item in items) {
            val candidate = item as? JsonObject
            if (candidate == null) {
                invalid++; continue
            }
            try {
                drafts += WritingCandidatePolicy.parse(candidate)
            } catch (_: WritingCandidateProtocolException) {
                invalid++
            }
        }
        return Batch(drafts, if (invalid > 0) mapOf("CANDIDATE_SCHEMA" to invalid) else emptyMap())
    }
}
