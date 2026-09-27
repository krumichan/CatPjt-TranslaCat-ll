package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingCandidateDraft
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingCandidatePolicy
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingNotePolicy
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionCommand
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionPort
import jp.co.translacat.languagelearning.shared.ai.ModelMessage
import jp.co.translacat.languagelearning.shared.ai.ModelTier
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.*

/** A Nano note proposal and an independent Mini note-only audit; task fields stay immutable. */
internal class WritingNoteLocalizationExecution(private val model: ModelExecutionPort) {
    internal data class Result(val draft: WritingCandidateDraft?, val reason: String)

    suspend fun localize(
        request: JsonObject,
        draft: WritingCandidateDraft,
        candidateId: String,
        targetBand: Int,
        deadlineUtc: Instant,
        reviewId: String = UUID.randomUUID().toString().replace("-", ""),
    ): Result {
        require(reviewId != candidateId) { "WRITING_NOTE_CORRELATION_REUSED" }
        val oldHash = WritingCandidatePolicy.contentHash(request, draft)
        val proposal = model.execute(
            ModelExecutionCommand(
                traceId = candidateId,
                instructions = WritingNotePolicy.localizationInstructions,
                messages = listOf(
                    ModelMessage(
                        "user",
                        WritingNotePolicy.localizationPrompt(
                            request, draft, candidateId, oldHash,
                        ),
                    ),
                ),
                tier = ModelTier.NANO,
                maxOutputTokens = 2048,
                deadlineUtc = deadlineUtc,
                responseSchema = WritingNotePolicy.localizationSchema,
                schemaName = "writing_note_localization",
                strict = true,
                taskName = "LANGUAGE_LEARNING_WRITING_NOTE_LOCALIZATION",
            ),
        ).output
        val revisedNote = WritingNotePolicy.proposal(proposal, candidateId, oldHash)
        if (revisedNote == draft.focusReason) return Result(null, "NOTE_LOCALIZATION_UNCHANGED")
        val revised = draft.copy(focusReason = revisedNote)
        val type = WritingType.valueOf(request.getValue("writingType").jsonPrimitive.content)
        if (WritingCandidatePolicy.noteNeedsLocalization(
                request.getValue("originLanguage").jsonPrimitive.content, revised,
            )
        )
            return Result(null, "NOTE_LOCALIZATION_SCRIPT_MISMATCH")
        WritingCandidatePolicy.reason(request, type, targetBand, revised)?.let { return Result(null, it) }
        val newHash = WritingCandidatePolicy.contentHash(request, revised)
        val reviewed = model.execute(
            ModelExecutionCommand(
                traceId = reviewId,
                instructions = WritingNotePolicy.reviewInstructions,
                messages = listOf(
                    ModelMessage(
                        "user",
                        WritingNotePolicy.reviewPrompt(
                            request, revised, reviewId, newHash,
                        ),
                    ),
                ),
                tier = ModelTier.MINI,
                maxOutputTokens = 2048,
                deadlineUtc = deadlineUtc,
                responseSchema = WritingNotePolicy.reviewSchema(revised),
                schemaName = "writing_note_review",
                strict = true,
                taskName = "LANGUAGE_LEARNING_WRITING_NOTE_VERIFICATION",
            ),
        ).output
        val outcome = WritingNotePolicy.review(reviewed, reviewId, newHash, revised)
        return if (outcome == "VERIFIED_NOTE_LOCALIZED") Result(revised, outcome) else Result(null, outcome)
    }
}
