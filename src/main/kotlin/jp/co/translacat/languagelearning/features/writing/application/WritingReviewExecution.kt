package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.features.writing.domain.policy.*
import jp.co.translacat.languagelearning.shared.ai.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Mini 독립 검증을 실행하며 기존 일반 2회·보정/재판정 1회 한도를 유지한다. */
internal class WritingReviewExecution(
    private val model: ModelExecutionPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    internal data class Result(val review: WritingReview, val contentHash: String)

    suspend fun assess(
        request: JsonObject,
        draft: WritingCandidateDraft,
        candidateId: String,
        deadlineUtc: Instant,
        repairPlan: WritingDifficultyRepairPlan? = null,
        adjudicator: Boolean = false,
        sourceRecovery: WritingSourceRecovery.Evidence? = null,
        onModelCall: () -> Unit = {},
    ): Result {
        require(repairPlan == null || sourceRecovery == null)
        if (repairPlan != null) require(repairPlan.changeReason(request, draft) == null) {
            "DIFFICULTY_REPAIR_CHANGED_OUTSIDE_SCOPE"
        }
        val contentHash = WritingCandidatePolicy.contentHash(request, draft)
        val revisionHash = repairPlan?.revisionHash(request, draft)
        val recoveryHash = sourceRecovery?.bindingHash(request, draft)
        val evidence = WritingDraftEvidence(
            draft.originText, draft.providedFacts, draft.requiredIntents,
            draft.responseConstraints, draft.focusReason,
        )

        // 기존 원문은 최종 학습 문항에서 숨기고, 별도 의미 보존 근거로만 검증 요청에 결합한다.
        val command = ModelExecutionCommand(
            traceId = candidateId,
            instructions = if (adjudicator) WritingReviewPrompt.adjudicatorInstructions
            else WritingReviewPrompt.instructions,
            messages = listOf(
                ModelMessage(
                    "user",
                    WritingReviewPrompt.build(
                        request, draft, candidateId, contentHash, repairPlan, sourceRecovery,
                    ),
                ),
            ),
            tier = ModelTier.MINI,
            maxOutputTokens = if (adjudicator) 2048 else 4096,
            deadlineUtc = deadlineUtc,
            responseSchema = WritingReviewSchema.build(evidence, candidateId, contentHash, revisionHash, recoveryHash),
            schemaName = "writing_task_review",
            strict = true,
            taskName = if (adjudicator) "LANGUAGE_LEARNING_WRITING_DIFFICULTY_VERIFICATION"
            else "LANGUAGE_LEARNING_WRITING_TASK_VERIFICATION",
        )
        val attemptLimit = if (adjudicator || repairPlan != null) 1 else 2
        for (attempt in 1..attemptLimit) {
            try {
                // 일반 검증의 기존 1회 복구만 허용하고 같은 후보 ID·Schema·deadline을 유지한다.
                onModelCall()
                val raw = model.execute(command).output as? JsonObject
                    ?: throw WritingReviewProtocolException("VERIFIER_RESPONSE_TYPE_INVALID")

                // Provider 출력은 승인 전에 Pydantic과 같은 교차 필드 및 입력 결합 조건을 다시 검사한다.
                val review = WritingReviewParser.parse(
                    raw, repaired = repairPlan != null,
                    recovered = sourceRecovery != null,
                )
                WritingReviewBinding.failure(
                    review, evidence, candidateId, contentHash,
                    revisionHash, recoveryHash,
                )?.let { throw WritingReviewProtocolException(it) }
                return Result(review, contentHash)
            } catch (failure: WritingReviewProtocolException) {
                if (attempt == attemptLimit || !clock.instant().isBefore(deadlineUtc)) throw failure
            } catch (failure: ModelExecutionFailure) {
                val remaining = Duration.between(clock.instant(), deadlineUtc).toMillis()
                if (attempt == attemptLimit || !failure.retryable || remaining <= 300) throw failure
                delay(250)
            }
        }
        error("WRITING_REVIEW_ATTEMPT_LIMIT")
    }
}
