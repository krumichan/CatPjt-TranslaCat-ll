package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.features.writing.domain.model.NewWritingItem
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingDifficulty
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingItem
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.*
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*

/** 생성 중인 Writing 세트에 검증된 문항만 게시한다. 남은 복구 정책은 전환 전 검증한다. */
internal class WritingGenerationWorker(
    private val state: WritingGenerationState,
    private val work: WritingSetUnitOfWork,
    private val generation: WritingGenerationExecution,
    private val review: WritingReviewExecution,
    private val clock: Clock = Clock.systemUTC(),
    private val candidateId: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    private val note: WritingNoteLocalizationExecution? = null,
    private val sourceRecovery: WritingSourceRecoveryExecution? = null,
    private val regeneration: WritingRegenerationState? = null,
) {
    internal data class Result(
        val published: Int,
        val generationCalls: Int,
        val reviewCalls: Int,
        val failureCode: String? = null,
        val diagnosticCode: String? = null,
        val failureStage: String? = null,
    )

    suspend fun process(userId: Long, setId: Long, request: JsonObject): Result =
        processInternal(userId, setId, request, null)

    suspend fun regenerate(userId: Long, setId: Long, request: JsonObject): Result {
        return regenerateClaimed(userId, setId) { request }
    }

    suspend fun regenerateFromSnapshot(userId: Long, setId: Long): Result =
        regenerateClaimed(userId, setId) { claim ->
            // 대상 난이도와 원래 snapshot을 고정하고 교체할 문항 수만 새 요청에 반영한다.
            val set = checkNotNull(state.find(userId, setId)) { "WRITING_SET_NOT_FOUND" }
            val snapshot = Json.parseToJsonElement(set.snapshotJson).jsonObject
            val counts = claim.targets.map { it.difficulty }.groupingBy { it }.eachCount()
            JsonObject(
                snapshot + mapOf(
                    "requestId" to JsonPrimitive("daily-regen-$setId-${set.regenerationCount + 1}-${claim.token}"),
                    "snapshotId" to JsonPrimitive(set.snapshotId),
                    "sentenceCount" to JsonPrimitive(claim.targets.size),
                    "difficultyDistribution" to buildJsonObject {
                        put("review", counts[WritingDifficulty.REVIEW] ?: 0)
                        put("normal", counts[WritingDifficulty.NORMAL] ?: 0)
                        put("challenge", counts[WritingDifficulty.CHALLENGE] ?: 0)
                    },
                ),
            )
        }

    private suspend fun regenerateClaimed(
        userId: Long, setId: Long,
        requestFor: suspend (WritingRegenerationState.Claim) -> JsonObject,
    ): Result {
        val state = checkNotNull(regeneration) { "WRITING_REGENERATION_UNAVAILABLE" }
        val claim = state.claim(userId, setId)
        var committed = false
        try {
            // 후보는 메모리에만 모으고 모든 슬롯 검증을 마친 뒤 기존 미응답 문항을 한 번에 교체한다.
            val result = processInternal(userId, setId, requestFor(claim), claim)
            committed = result.failureCode == null
            return if (committed) result else result.copy(published = 0)
        } finally {
            if (!committed) withContext(NonCancellable) { state.release(claim) }
        }
    }

    private suspend fun processInternal(
        userId: Long, setId: Long, request: JsonObject,
        regenerationClaim: WritingRegenerationState.Claim?,
    ): Result {
        // 저장된 snapshot과 요청을 비교해 다른 설정의 문항이 세트에 섞이지 않게 한다.
        val set = state.find(userId, setId) ?: return Result(0, 0, 0, "WRITING_SET_NOT_FOUND")
        val snapshotIdentity = request["snapshotId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: request.getValue("requestId").jsonPrimitive.content
        require(
            snapshotIdentity == set.snapshotId &&
                request.getValue("writingType").jsonPrimitive.content == set.writingType.name &&
                request.getValue("originLanguage").jsonPrimitive.content.isNotBlank() &&
                request.getValue("learningLanguage").jsonPrimitive.content.isNotBlank(),
        ) {
            "WRITING_GENERATION_REQUEST_MISMATCH"
        }
        val snapshot = Json.parseToJsonElement(set.snapshotJson).jsonObject
        for (field in listOf(
            "originLanguage", "learningLanguage", "sentenceCount", "difficultyDistribution",
            "selectedKeywords", "learningProfile", "recentEvaluationSummary", "recentMistakes",
            "recentlyLearnedExpressions", "generationDate", "snapshotId",
        )) {
            if (regenerationClaim != null && field in setOf("sentenceCount", "difficultyDistribution")) continue
            if (field in snapshot) require(snapshot[field] == request[field]) {
                "WRITING_GENERATION_SNAPSHOT_MISMATCH"
            }
        }
        val distribution = request.getValue("difficultyDistribution").jsonObject
        val slots = buildList {
            for ((difficulty, field) in listOf(
                WritingDifficulty.REVIEW to "review", WritingDifficulty.NORMAL to "normal",
                WritingDifficulty.CHALLENGE to "challenge",
            )) repeat(distribution.getValue(field).jsonPrimitive.int) { add(difficulty) }
        }
        val expectedCount = regenerationClaim?.targets?.size ?: set.sentenceCount
        require(
            slots.size == expectedCount && request.getValue("sentenceCount").jsonPrimitive.int == expectedCount &&
                (regenerationClaim == null || slots.groupingBy { it }.eachCount() ==
                    regenerationClaim.targets.map { it.difficulty }.groupingBy { it }.eachCount()),
        ) {
            "WRITING_GENERATION_SLOT_MISMATCH"
        }

        // 전체 deadline과 기존 슬롯별 생성 한도를 고정한다.
        val baseBand = request.getValue("languageComplexity").jsonObject["baseComplexityBand"]?.jsonPrimitive?.int ?: 3
        val overallDeadline = clock.instant().plus(Duration.ofSeconds(240))
        var published = 0
        var generationCalls = 0
        var reviewCalls = 0
        val stagedItems = mutableListOf<NewWritingItem>()
        while (true) {
            // lease를 얻은 슬롯에 현재 세트와 로컬 이력을 결합해 중복 판정 문맥을 만든다.
            val claim = if (regenerationClaim == null) state.claimNext(userId, setId) else
                if (stagedItems.size < regenerationClaim.targets.size)
                    WritingGenerationState.GenerationClaim(stagedItems.size + 1, regenerationClaim.token) else null
            if (claim == null) {
                if (regenerationClaim != null) {
                    val applied = checkNotNull(regeneration).publish(regenerationClaim, stagedItems)
                    return Result(
                        if (applied) stagedItems.size else 0, generationCalls, reviewCalls,
                        if (applied) null else "WRITING_REGENERATION_STALE_CLAIM",
                    )
                }
                return Result(published, generationCalls, reviewCalls)
            }
            val difficulty = slots[claim.order - 1]
            val targetBand = WritingDifficultyPolicy.targetBand(baseBand, difficulty.name)
            val accepted = work.read { items.list(userId, setId) }
                .filterNot { item -> regenerationClaim?.targets?.any { it.itemId == item.id } == true } +
                stagedItems.mapIndexed { index, item -> stagedItem(userId, setId, -index.toLong() - 1, item) }
            val localHistory = work.read {
                fingerprints.context(
                    userId,
                    request.getValue("learningLanguage").jsonPrimitive.content,
                    LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                )
            }
            val scopedRequest = withAcceptedHistory(mergeHistory(request, localHistory), accepted)
            val strict = WritingDiversityValidator(
                scopedRequest["diversityContext"] as? JsonObject
                    ?: JsonObject(emptyMap()),
            )
            val relaxed = WritingDiversityValidator(
                scopedRequest["diversityContext"] as? JsonObject
                    ?: JsonObject(emptyMap()),
                relaxedHistory = true,
            )
            val acceptedPairs = accepted.mapNotNull { item ->
                item.diversityMetadataJson?.let { item.originText to Json.parseToJsonElement(it).jsonObject }
            }
            val feedback = mutableMapOf<String, Int>()
            val candidateMemory = WritingCandidateMemory()
            var fallback: Pair<WritingCandidateDraft, JsonObject>? = null
            var adjacentRecheckUsed = false
            var attemptLimit = 4
            var repairUsed = false
            var pendingRepair: WritingDifficultyRepairPlan? = null
            var sourceRecoveryUsed = false
            var sourceRecoveryMode = false
            var sourceExhausted = false
            val difficultyControl = WritingDifficultyRecovery(targetBand)
            var slotPublished = false
            var failureStage = "GENERATION"
            suspend fun publish(item: NewWritingItem): Boolean {
                if (regenerationClaim != null) {
                    stagedItems += item
                    return true
                }
                return state.publishItem(userId, setId, claim, item, "writing-generation-targeted-repair-v1")
            }
            try {
                for (attempt in 1..4) {
                    // 남은 예산 안에서 후보를 만들고, 모델 응답 전의 코드 판정을 먼저 수행한다.
                    if (attempt > attemptLimit) break
                    if (!clock.instant().isBefore(overallDeadline)) break
                    val activeRepair = pendingRepair
                    pendingRepair = null
                    val repairTimeAvailable = Duration.between(clock.instant(), overallDeadline).seconds >= 60
                    if (activeRepair != null && !repairTimeAvailable) {
                        feedback["DIFFICULTY_REPAIR_TIME_BUDGET"] = 1
                        break
                    }
                    generationCalls++
                    failureStage = "GENERATION"
                    val batch = try {
                        if (activeRepair != null) generation.repair(
                            scopedRequest, activeRepair,
                            minOf(overallDeadline, clock.instant().plusSeconds(30)),
                        ) else generation.generate(
                            scopedRequest, set.writingType, targetBand, attempt,
                            minOf(overallDeadline, clock.instant().plusSeconds(30)), feedback,
                            sourceRecoveryMode = sourceRecoveryMode,
                            difficultyRecovery = if (repairUsed) buildJsonObject {
                                put("policyVersion", "writing-difficulty-targeted-repair-v2")
                                put("direction", "INCREASE_PRODUCTION_DEMAND")
                                put("targetBand", targetBand)
                                put(
                                    "instruction",
                                    "Final fresh recovery after a bounded editorial revision; " +
                                        "realize the productionBlueprint in actual content, not labels or padding.",
                                )
                            } else difficultyControl.payload(),
                        )
                    } catch (failure: ModelExecutionFailure) {
                        // 원본처럼 생성 거부/불완전 출력은 후보를 버리고 남은 기존 슬롯 시도만 사용한다.
                        val rejected = writingGeneratorRejection(failure.code)
                        if (rejected != null) {
                            feedback[rejected] = (feedback[rejected] ?: 0) + 1
                            continue
                        }

                        // 일시적 생성 의존성 실패만 남은 기존 시도와 deadline 안에서 재호출한다.
                        val retryDelay = maxOf(250L, (failure.retryAfterSeconds ?: 0L) * 1000)
                        if (activeRepair != null || attempt >= attemptLimit || !failure.retryable ||
                            failure.code !in setOf(
                                "PROVIDER_TIMEOUT", "PROVIDER_UNAVAILABLE",
                                "MODEL_EXECUTION_TRANSPORT",
                            ) ||
                            retryDelay > 5_000 ||
                            Duration.between(clock.instant(), overallDeadline).toMillis() <= retryDelay + 50
                        ) throw failure
                        val code = when (failure.code) {
                            "PROVIDER_TIMEOUT" -> "GENERATOR_TIMEOUT"
                            "MODEL_EXECUTION_TRANSPORT" -> "GENERATOR_CONNECTION_ERROR"
                            else -> "GENERATOR_PROVIDER_ERROR"
                        }
                        feedback[code] = (feedback[code] ?: 0) + 1
                        delay(retryDelay)
                        continue
                    }
                    failureStage = "VERIFICATION"
                    batch.rejections.forEach { (code, count) -> feedback[code] = (feedback[code] ?: 0) + count }
                    val repairCandidates = mutableListOf<WritingDifficultyRepairPlan>()
                    val sourceOnly = mutableListOf<WritingCandidateDraft>()
                    for (draft in batch.drafts) {
                        val reason = WritingCandidatePolicy.reason(scopedRequest, set.writingType, targetBand, draft)
                        if (reason != null) {
                            feedback[reason] = (feedback[reason] ?: 0) + 1
                            if (reason == "ORIGIN_TEXT_SCRIPT_MISMATCH") sourceOnly += draft
                            continue
                        }
                        val repeated = candidateMemory.check(scopedRequest, draft)
                        if (repeated != null) {
                            feedback[repeated] = (feedback[repeated] ?: 0) + 1
                            continue
                        }
                        val metadata = WritingCandidatePolicy.metadata(draft.metadata)
                        val decision = strict.validate(draft.originText, metadata, acceptedPairs)
                        val eligible = if (decision.accepted) decision else
                            relaxed.validate(draft.originText, metadata, acceptedPairs)
                        if (!eligible.accepted) {
                            val code = "DIVERSITY_${decision.reason}"
                            feedback[code] = (feedback[code] ?: 0) + 1
                            continue
                        }

                        // 후보의 의미·난이도를 독립 검증하고 필요한 한 번의 보정 또는 재검증만 사용한다.
                        val firstCandidateId = candidateId()
                        val assessment = review.assess(
                            scopedRequest, draft, firstCandidateId,
                            minOf(overallDeadline, clock.instant().plusSeconds(30)), activeRepair,
                            onModelCall = { reviewCalls++ },
                        )
                        val repairPlan = if (activeRepair == null && !repairUsed && !sourceRecoveryUsed &&
                            difficultyControl.direction == null && attempt < attemptLimit &&
                            repairTimeAvailable && decision.accepted
                        )
                            WritingDifficultyRepairPlan.plan(
                                scopedRequest, draft, claim.order,
                                set.writingType, targetBand, assessment.review,
                            ) else null
                        var finalReview = assessment.review
                        var verdict = WritingReviewAcceptance.decide(
                            finalReview, targetBand, set.writingType,
                            allowAdjacentRecheck = !adjacentRecheckUsed && repairPlan == null,
                            repaired = activeRepair != null,
                        )
                        if (verdict.action == "ADJUDICATE") {
                            if (verdict.reason == "ADJACENT_BAND_RECHECK") adjacentRecheckUsed = true
                            val secondCandidateId = candidateId()
                            check(secondCandidateId != firstCandidateId) { "WRITING_REVIEW_CORRELATION_REUSED" }
                            val second = review.assess(
                                scopedRequest, draft, secondCandidateId,
                                minOf(overallDeadline, clock.instant().plusSeconds(30)), adjudicator = true,
                                onModelCall = { reviewCalls++ },
                            )
                            finalReview = second.review
                            verdict = WritingReviewAcceptance.decide(
                                finalReview, targetBand, set.writingType,
                                adjudicated = true, allowAdjacentRecheck = false,
                            )
                        }
                        val noteNeedsLocalization = WritingCandidatePolicy.noteNeedsLocalization(
                            scopedRequest.getValue("originLanguage").jsonPrimitive.content, draft,
                        )
                        var approvedDraft = draft
                        if (verdict.action == "LOCALIZE_NOTE" ||
                            (verdict.action == "ACCEPT" && noteNeedsLocalization)
                        ) {
                            if (activeRepair != null || note == null) {
                                val code = if (activeRepair != null) "DIFFICULTY_REPAIR_NOTE_INVALID"
                                else "NOTE_LOCALIZATION_UNAVAILABLE"
                                feedback[code] = (feedback[code] ?: 0) + 1
                                continue
                            }
                            val noteReviewId = candidateId()
                            check(noteReviewId != firstCandidateId) { "WRITING_NOTE_CORRELATION_REUSED" }
                            reviewCalls += 2
                            val localized = note.localize(
                                scopedRequest, draft, firstCandidateId, targetBand,
                                minOf(overallDeadline, clock.instant().plusSeconds(30)), noteReviewId,
                            )
                            if (localized.draft == null) {
                                feedback[localized.reason] = (feedback[localized.reason] ?: 0) + 1
                                continue
                            }
                            approvedDraft = localized.draft
                        } else if (verdict.action != "ACCEPT") {
                            candidateMemory.recordRejection(draft, verdict.reason)
                            difficultyControl.record(
                                finalReview.estimatedBand, finalReview.difficultyStatus,
                                verdict.reason, attempt,
                            )
                            feedback[verdict.reason] = (feedback[verdict.reason] ?: 0) + 1
                            if (repairPlan != null && verdict.reason == "VERIFIED_BAND_MISMATCH")
                                repairCandidates += repairPlan
                            continue
                        }
                        if (decision.accepted) {
                            // 승인된 문항은 현재 lease의 토큰이 유효할 때만 게시한다.
                            val item = item(claim.order, difficulty, targetBand, approvedDraft, eligible.metadata)
                            if (publish(item)) {
                                published++
                                slotPublished = true
                                fallback = null
                                break
                            }
                            return Result(published, generationCalls, reviewCalls, "WRITING_GENERATION_STALE_CLAIM")
                        }
                        if (fallback == null) fallback = approvedDraft to eligible.metadata
                    }
                    if (slotPublished || fallback == null && state.find(
                            userId, setId,
                        )?.generationToken != claim.token
                    ) break
                    if (activeRepair == null && repairCandidates.isNotEmpty() && fallback == null &&
                        Duration.between(clock.instant(), overallDeadline).seconds >= 60
                    ) {
                        pendingRepair = repairCandidates.first()
                        repairUsed = true
                        attemptLimit = minOf(attemptLimit, attempt + 2)
                    }
                    if (pendingRepair != null) continue
                    val allSourceOnly = batch.drafts.isNotEmpty() && sourceOnly.size == batch.drafts.size &&
                        batch.rejections.isEmpty()
                    if (set.writingType == WritingType.TRANSLATION && allSourceOnly && !repairUsed) {
                        sourceExhausted = true
                        if (sourceRecoveryUsed) break
                        sourceRecoveryUsed = true
                        sourceRecoveryMode = true
                        attemptLimit = minOf(attemptLimit, attempt + 1)

                        // 같은 배치의 모든 후보가 원문 script만 실패했을 때 한 번만 필드 제안을 요청한다.
                        if (sourceRecovery != null) {
                            val inputs = sourceOnly.map { WritingSourceRecovery.input(candidateId(), it) }
                            val proposals = try {
                                reviewCalls++
                                sourceRecovery.propose(
                                    scopedRequest, targetBand, inputs, candidateId(),
                                    minOf(overallDeadline, clock.instant().plusSeconds(30)),
                                )
                            } catch (failure: WritingReviewProtocolException) {
                                feedback[failure.code] = (feedback[failure.code] ?: 0) + 1
                                emptyList()
                            }
                            for (proposal in proposals) {
                                val draft = proposal.draft
                                val reason = WritingCandidatePolicy.reason(
                                    scopedRequest, set.writingType,
                                    targetBand, draft,
                                )
                                if (reason != null) {
                                    feedback["SOURCE_RECOVERY_$reason"] =
                                        (feedback["SOURCE_RECOVERY_$reason"] ?: 0) + 1
                                    continue
                                }
                                val repeated = candidateMemory.check(
                                    scopedRequest, draft,
                                    proposal.evidence.bindingHash(scopedRequest, draft),
                                )
                                if (repeated != null) {
                                    feedback[repeated] = (feedback[repeated] ?: 0) + 1
                                    continue
                                }
                                sourceExhausted = false
                                val metadata = WritingCandidatePolicy.metadata(draft.metadata)
                                val decision = strict.validate(draft.originText, metadata, acceptedPairs)
                                val eligible = if (decision.accepted) decision else
                                    relaxed.validate(draft.originText, metadata, acceptedPairs)
                                if (!eligible.accepted) {
                                    val code = "DIVERSITY_${decision.reason}"
                                    feedback[code] = (feedback[code] ?: 0) + 1
                                    continue
                                }

                                // 수정된 문항 전체를 새로운 Mini 응답으로 검증하며 원문 S0와 최종 O1을 결합한다.
                                val firstId = candidateId()
                                val assessment = review.assess(
                                    scopedRequest, draft, firstId,
                                    minOf(overallDeadline, clock.instant().plusSeconds(30)),
                                    sourceRecovery = proposal.evidence, onModelCall = { reviewCalls++ },
                                )
                                var finalReview = assessment.review
                                var verdict = WritingReviewAcceptance.decide(
                                    finalReview, targetBand,
                                    set.writingType, allowAdjacentRecheck = !adjacentRecheckUsed,
                                )
                                if (verdict.action == "ADJUDICATE") {
                                    if (verdict.reason == "ADJACENT_BAND_RECHECK") adjacentRecheckUsed = true
                                    val secondId = candidateId()
                                    check(secondId != firstId) { "WRITING_REVIEW_CORRELATION_REUSED" }
                                    val second = review.assess(
                                        scopedRequest, draft, secondId,
                                        minOf(overallDeadline, clock.instant().plusSeconds(30)),
                                        adjudicator = true, sourceRecovery = proposal.evidence,
                                        onModelCall = { reviewCalls++ },
                                    )
                                    finalReview = second.review
                                    verdict = WritingReviewAcceptance.decide(
                                        finalReview, targetBand,
                                        set.writingType, adjudicated = true, allowAdjacentRecheck = false,
                                    )
                                }
                                var approvedDraft = draft
                                val noteNeedsLocalization = WritingCandidatePolicy.noteNeedsLocalization(
                                    scopedRequest.getValue("originLanguage").jsonPrimitive.content, draft,
                                )
                                if (verdict.action == "LOCALIZE_NOTE" ||
                                    (verdict.action == "ACCEPT" && noteNeedsLocalization)
                                ) {
                                    if (note == null) {
                                        feedback["NOTE_LOCALIZATION_UNAVAILABLE"] =
                                            (feedback["NOTE_LOCALIZATION_UNAVAILABLE"] ?: 0) + 1
                                        continue
                                    }
                                    val noteReviewId = candidateId()
                                    check(noteReviewId != firstId) { "WRITING_NOTE_CORRELATION_REUSED" }
                                    reviewCalls += 2
                                    val localized = note.localize(
                                        scopedRequest, draft, firstId, targetBand,
                                        minOf(overallDeadline, clock.instant().plusSeconds(30)), noteReviewId,
                                    )
                                    if (localized.draft == null) {
                                        feedback[localized.reason] = (feedback[localized.reason] ?: 0) + 1
                                        continue
                                    }
                                    approvedDraft = localized.draft
                                } else if (verdict.action != "ACCEPT") {
                                    candidateMemory.recordRejection(draft, verdict.reason)
                                    difficultyControl.record(
                                        finalReview.estimatedBand, finalReview.difficultyStatus,
                                        verdict.reason, attempt,
                                    )
                                    feedback[verdict.reason] = (feedback[verdict.reason] ?: 0) + 1
                                    continue
                                }
                                val item = item(claim.order, difficulty, targetBand, approvedDraft, eligible.metadata)
                                if (decision.accepted) {
                                    if (!publish(item))
                                        return Result(
                                            published, generationCalls, reviewCalls,
                                            "WRITING_GENERATION_STALE_CLAIM",
                                        )
                                    published++
                                    slotPublished = true
                                    fallback = null
                                    break
                                }
                                if (fallback == null) fallback = approvedDraft to eligible.metadata
                            }
                        }
                    }
                    // 최종 검증의 확정 불일치 두 건만 남은 생성 시도 한 차례로 수렴시킨다.
                    if (difficultyControl.selectAfterRound(attempt, attemptLimit))
                        attemptLimit = minOf(attemptLimit, attempt + 1)
                    if (slotPublished || fallback == null && state.find(
                            userId, setId,
                        )?.generationToken != claim.token
                    ) break
                }
                if (slotPublished) continue
                if (state.find(userId, setId)?.generationToken != claim.token) {
                    if (regenerationClaim != null) return Result(
                        0, generationCalls, reviewCalls,
                        "WRITING_REGENERATION_STALE_CLAIM",
                    )
                    continue
                }
                if (fallback != null) {
                    val item = item(claim.order, difficulty, targetBand, fallback.first, fallback.second)
                    if (publish(item)) {
                        published++
                        continue
                    }
                    return Result(published, generationCalls, reviewCalls, "WRITING_GENERATION_STALE_CLAIM")
                }
                val failureCode = when {
                    !clock.instant().isBefore(overallDeadline) -> "WRITING_GENERATION_DEADLINE_EXCEEDED"
                    sourceExhausted -> "WRITING_SOURCE_LANGUAGE_EXHAUSTED"
                    else -> "WRITING_GENERATION_VALIDATION_EXHAUSTED"
                }
                // 원문 없이 합법적인 생성 거부와 검증/시스템 실패를 구분할 진단을 남긴다.
                org.slf4j.LoggerFactory.getLogger(WritingGenerationWorker::class.java).warn(
                    "Writing generation exhausted. code={} reasons={} generationCalls={} reviewCalls={}",
                    failureCode, feedback.toMap(), generationCalls, reviewCalls,
                )
                if (regenerationClaim == null) state.fail(userId, setId, claim.token, failureCode)
                return Result(published, generationCalls, reviewCalls, failureCode)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ModelExecutionFailure) {
                if (regenerationClaim == null) state.fail(userId, setId, claim.token, failure.code)
                return Result(published, generationCalls, reviewCalls, failure.code, failureStage = failureStage)
            } catch (failure: WritingReviewProtocolException) {
                if (regenerationClaim == null) state.fail(userId, setId, claim.token, "VERIFIER_SCHEMA_INVALID")
                return Result(
                    published, generationCalls, reviewCalls, "VERIFIER_SCHEMA_INVALID", failure.code,
                    failureStage = "VERIFICATION",
                )
            }
        }
    }

    private fun item(
        order: Int, difficulty: WritingDifficulty, band: Int, draft: WritingCandidateDraft,
        metadata: JsonObject,
    ) = NewWritingItem(
        order, difficulty, draft.originText, draft.keywords, draft.focusMetrics,
        draft.focusReason, draft.providedFacts, draft.requiredIntents, draft.responseConstraints,
        band, metadata.toString(),
    )

    private fun stagedItem(userId: Long, setId: Long, id: Long, item: NewWritingItem): WritingItem {
        fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive)).toString()
        return WritingItem(
            id, setId, userId, item.order, item.difficulty, item.originText,
            strings(item.keywords), strings(item.focusMetrics), item.focusReason,
            strings(item.providedFacts), strings(item.requiredIntents), strings(item.responseConstraints),
            item.languageComplexityBand, item.diversityMetadataJson,
        )
    }

    private fun withAcceptedHistory(request: JsonObject, accepted: List<WritingItem>): JsonObject {
        val context = request["diversityContext"] as? JsonObject ?: JsonObject(emptyMap())
        val existing = (context["currentSession"] as? JsonArray).orEmpty().toMutableList()
        for (item in accepted) {
            if (existing.any { it.jsonObject["content"]?.jsonPrimitive?.content == item.originText }) continue
            val metadata = item.diversityMetadataJson?.let { Json.parseToJsonElement(it).jsonObject }
            existing += buildJsonObject {
                put("sourceType", "WRITING")
                put("content", item.originText)
                put("contentHash", metadata?.get("contentHash") ?: JsonNull)
                put("scenarioCategory", metadata?.get("scenarioCategory") ?: JsonNull)
                put("communicativeIntent", metadata?.get("communicativeIntent") ?: JsonNull)
                put("taskArchetype", metadata?.get("taskArchetype") ?: JsonNull)
                put("grammarFocusCodes", metadata?.get("grammarFocusCodes") ?: JsonArray(emptyList()))
                put("semanticSummary", metadata?.get("semanticSummary") ?: JsonNull)
                put("ageDays", 0)
            }
        }
        return JsonObject(
            request + ("diversityContext" to JsonObject(
                context +
                    ("currentSession" to JsonArray(existing.takeLast(40))),
            )),
        )
    }

    private fun mergeHistory(request: JsonObject, local: JsonObject): JsonObject {
        val original = request["diversityContext"] as? JsonObject ?: JsonObject(emptyMap())
        fun array(value: JsonObject, name: String) = (value[name] as? JsonArray).orEmpty()
        fun recent(name: String, limit: Int): JsonArray = JsonArray(
            (array(local, name) + array(original, name)).distinctBy { entry ->
                val row = entry.jsonObject
                "${row["sourceType"]}|${row["contentHash"]}|${row["content"]}"
            }.sortedBy { it.jsonObject["ageDays"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }.take(limit),
        )

        val exact = (array(local, "exactContentHashes90d") + array(original, "exactContentHashes90d"))
            .distinct().take(200)
        val context = JsonObject(
            original + mapOf(
                "currentSession" to JsonArray(array(original, "currentSession").takeLast(40)),
                "sameFeatureRecent" to recent("sameFeatureRecent", 80),
                "crossFeatureRecent" to recent("crossFeatureRecent", 40),
                "exactContentHashes90d" to JsonArray(exact),
            ),
        )
        return JsonObject(request + ("diversityContext" to context))
    }
}
