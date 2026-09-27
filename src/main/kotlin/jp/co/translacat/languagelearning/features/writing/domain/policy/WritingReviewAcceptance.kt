package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*

internal class WritingReviewProtocolException(val code: String) : RuntimeException(code)

internal data class WritingCriterion(val criterion: String, val status: String, val evidenceIds: List<String>)
internal data class WritingDemand(
    val code: String, val status: String, val evidenceIds: List<String>, val gapCode: String?,
)

internal data class WritingPreservation(val status: String, val issues: List<String>, val evidenceIds: List<String>)
internal data class WritingReview(
    val candidateId: String,
    val contentHash: String,
    val revisionHash: String?,
    val verdict: String,
    val observedType: String,
    val difficultyStatus: String,
    val difficultyEvidenceIds: List<String>,
    val estimatedBand: Int?,
    val alternativeBand: Int?,
    val checks: List<WritingCriterion>,
    val issues: List<String>,
    val demands: List<WritingDemand>?,
    val preservation: WritingPreservation?,
    val recoveryHash: String?,
    val sourcePreservation: WritingPreservation?,
)

internal data class WritingAcceptance(val action: String, val reason: String)

/** Python assessment_contract.py의 TaskReview/RepairedTaskReview 교차 필드 계약. */
internal object WritingReviewParser {
    private val criteria = setOf("ORIGIN_LANGUAGE", "TASK_VALIDITY", "ANSWER_LEAK", "NATURALNESS", "NOTE_QUALITY")
    private val issueCriterion = mapOf(
        "ORIGIN_LANGUAGE" to "ORIGIN_LANGUAGE", "ANSWER_LEAK" to "ANSWER_LEAK",
        "TASK_TYPE" to "TASK_VALIDITY", "MISSING_FACTS" to "TASK_VALIDITY",
        "CONTRADICTORY_GUIDANCE" to "TASK_VALIDITY", "AMBIGUOUS_TASK" to "TASK_VALIDITY",
        "BACKGROUND_KNOWLEDGE" to "TASK_VALIDITY", "NOT_LANGUAGE_TASK" to "TASK_VALIDITY",
        "UNNATURAL_LANGUAGE" to "NATURALNESS", "NOTE_ORIGIN_LANGUAGE" to "NOTE_QUALITY",
        "UNSUPPORTED_FOCUS_REASON" to "NOTE_QUALITY", "INTERNAL_CLAIM" to "NOTE_QUALITY",
        "DIVERSITY_METADATA_MISMATCH" to "TASK_VALIDITY",
        "DIVERSITY_SCENE_REPETITION" to "TASK_VALIDITY", "KEYWORD_SCOPE_MISMATCH" to "TASK_VALIDITY",
    )
    private val demandCodes = setOf("SCOPE_INTERACTION", "PRECISE_STANCE", "COHERENT_REGISTER")
    private val gapDemand = mapOf(
        "RELATIONS_NOT_INTERDEPENDENT" to "SCOPE_INTERACTION",
        "SCOPE_TOO_ROUTINE" to "PRECISE_STANCE",
        "REGISTER_NOT_INTEGRATED" to "COHERENT_REGISTER",
    )
    private val preservationIssues = setOf(
        "SCENE_CHANGED", "FACT_CHANGED", "INTENT_CHANGED", "KEYWORD_CHANGED", "UNRELATED_ADDITION", "COSMETIC_ONLY",
    )
    private val sourceIssues = setOf(
        "MEANING_CHANGED", "FACT_CHANGED", "POLARITY_CHANGED", "REGISTER_CHANGED", "UNSUPPORTED_ADDITION",
    )

    fun parse(raw: JsonObject, repaired: Boolean = false, recovered: Boolean = false): WritingReview {
        require(!repaired || !recovered)
        // 응답 종류별 필드 집합을 닫고 기본 판정·근거의 교차 조건을 검사한다.
        val baseKeys = setOf(
            "candidateId", "contentHash", "confidence", "verdict", "observedWritingType",
            "difficultyStatus", "estimatedBand", "alternativeBand", "difficultyConfidence",
            "difficultyEvidenceSegmentIds", "checks", "issues", "productionDemandChecks",
        )
        val expectedKeys = when {
            repaired -> baseKeys + setOf("revisionHash", "revisionPreservation")
            recovered -> baseKeys + setOf("recoveryHash", "sourcePreservation")
            else -> baseKeys
        }
        exact(raw, expectedKeys)
        val candidateId = text(raw, "candidateId", 1, 100)
        val contentHash = text(raw, "contentHash")
        if (!Regex("[a-f0-9]{64}").matches(contentHash)) invalid("CONTENT_HASH_INVALID")
        confidence(raw, "confidence")
        confidence(raw, "difficultyConfidence")
        val verdict = choice(raw, "verdict", setOf("PASS", "REJECT", "UNSURE"))
        val observed = choice(raw, "observedWritingType", setOf("TRANSLATION", "GUIDED", "FREE", "UNSURE"))
        val status = choice(raw, "difficultyStatus", setOf("ASSESSED", "BORDERLINE", "UNSURE"))
        val estimated = band(raw, "estimatedBand")
        val alternative = band(raw, "alternativeBand")
        val difficultyIds = ids(raw, "difficultyEvidenceSegmentIds")
        val checks = array(raw, "checks", 5, 5).map { element ->
            val value = element as? JsonObject ?: invalid("CRITERION_INVALID")
            exact(value, setOf("criterion", "status", "evidenceSegmentIds"))
            val criterion = choice(value, "criterion", criteria)
            val checkStatus = choice(value, "status", setOf("PASS", "FAIL", "UNSURE"))
            val evidence = ids(value, "evidenceSegmentIds")
            if (checkStatus != "UNSURE" && evidence.isEmpty()) invalid("CRITERION_EVIDENCE_MISSING")
            WritingCriterion(criterion, checkStatus, evidence)
        }
        if (checks.map { it.criterion }.toSet() != criteria) invalid("CRITERION_SET_INVALID")
        val issues = strings(raw, "issues", 12, issueCriterion.keys)
        if (issues.size != issues.toSet().size) invalid("ISSUE_DUPLICATE")

        val demands = if (raw["productionDemandChecks"] == JsonNull) null else
            array(raw, "productionDemandChecks", 3, 3).map { element ->
                val value = element as? JsonObject ?: invalid("DEMAND_INVALID")
                exact(value, setOf("code", "status", "evidenceSegmentIds", "gapCode"))
                val code = choice(value, "code", demandCodes)
                val demandStatus = choice(value, "status", setOf("PRESENT", "MISSING", "UNSURE"))
                val evidence = ids(value, "evidenceSegmentIds")
                if (demandStatus != "UNSURE" && evidence.isEmpty()) invalid("DEMAND_EVIDENCE_MISSING")
                val gap = optionalChoice(value, "gapCode", gapDemand.keys)
                if (gap != null && (demandStatus != "PRESENT" || gapDemand[gap] != code)) invalid("DEMAND_GAP_MISMATCH")
                WritingDemand(code, demandStatus, evidence, gap)
            }
        if (demands != null && demands.map { it.code }.toSet() != demandCodes) invalid("DEMAND_SET_INVALID")

        when (status) {
            "ASSESSED" -> if (estimated == null || alternative != null || difficultyIds.isEmpty()) invalid(
                "DIFFICULTY_STATUS_MISMATCH",
            )

            "BORDERLINE" -> if (estimated == null || alternative == null || difficultyIds.isEmpty() ||
                kotlin.math.abs(estimated - alternative) != 1
            ) invalid("DIFFICULTY_STATUS_MISMATCH")

            else -> if (estimated != null || alternative != null) invalid("DIFFICULTY_STATUS_MISMATCH")
        }
        val failed = checks.filter { it.status == "FAIL" }.map { it.criterion }.toSet()
        if (failed != issues.map { issueCriterion.getValue(it) }.toSet()) invalid("CRITERION_ISSUE_MISMATCH")
        val uncertain = checks.any { it.status == "UNSURE" } || observed == "UNSURE" || status != "ASSESSED"
        val expectedVerdict = if (failed.isNotEmpty()) "REJECT" else if (uncertain) "UNSURE" else "PASS"
        if (verdict != expectedVerdict) invalid("VERDICT_MISMATCH")

        val revisionHash = if (repaired) text(raw, "revisionHash") else null
        // 수정본은 이전 문항과 최종 문항을 모두 인용해야 하며 상태와 문제 목록이 일치해야 한다.
        val preservation = if (repaired) {
            if (revisionHash == null || !Regex("[a-f0-9]{64}").matches(revisionHash)) invalid("REVISION_HASH_INVALID")
            val value = raw["revisionPreservation"] as? JsonObject ?: invalid("PRESERVATION_INVALID")
            exact(value, setOf("status", "issues", "evidenceSegmentIds"))
            val preservationStatus = choice(value, "status", setOf("PASS", "FAIL", "UNSURE"))
            val preservationIssueList = strings(value, "issues", 6, preservationIssues)
            val evidence = ids(value, "evidenceSegmentIds")
            if ((preservationStatus == "FAIL") != preservationIssueList.isNotEmpty()) invalid(
                "REVISION_STATUS_ISSUE_MISMATCH",
            )
            if (preservationStatus != "UNSURE" && ("B0" !in evidence || evidence.none { it !in setOf("B0", "N1") })) {
                invalid("REVISION_EVIDENCE_INCOMPLETE")
            }
            if (demands == null) invalid("REPAIRED_DEMAND_MISSING")
            WritingPreservation(preservationStatus, preservationIssueList, evidence)
        } else null
        val recoveryHash = if (recovered) text(raw, "recoveryHash") else null
        // 원문 복구본은 숨겨진 원문 S0와 최종 O1의 의미 보존을 별도로 판정한다.
        val sourcePreservation = if (recovered) {
            if (recoveryHash == null || !Regex("[a-f0-9]{64}").matches(recoveryHash)) invalid("RECOVERY_HASH_INVALID")
            val value = raw["sourcePreservation"] as? JsonObject ?: invalid("SOURCE_PRESERVATION_INVALID")
            exact(value, setOf("status", "issues", "evidenceSegmentIds"))
            val sourceStatus = choice(value, "status", setOf("PASS", "FAIL", "UNSURE"))
            val sourceIssueList = strings(value, "issues", 5, sourceIssues)
            val evidence = ids(value, "evidenceSegmentIds")
            if (sourceIssueList.size != sourceIssueList.toSet().size || evidence.size != evidence.toSet().size)
                invalid("SOURCE_PRESERVATION_DUPLICATE")
            if ((sourceStatus == "FAIL") != sourceIssueList.isNotEmpty()) invalid("SOURCE_STATUS_ISSUE_MISMATCH")
            if (evidence.any { it !in setOf("S0", "O1") } ||
                (sourceStatus != "UNSURE" && evidence.toSet() != setOf("S0", "O1")))
                invalid("SOURCE_EVIDENCE_INCOMPLETE")
            WritingPreservation(sourceStatus, sourceIssueList, evidence)
        } else null
        return WritingReview(
            candidateId, contentHash, revisionHash, verdict, observed, status, difficultyIds,
            estimated, alternative, checks, issues, demands, preservation, recoveryHash, sourcePreservation,
        )
    }

    private fun exact(value: JsonObject, keys: Set<String>) {
        if (value.keys != keys) invalid("SCHEMA_FIELDS_INVALID")
    }

    private fun text(value: JsonObject, key: String, min: Int = 1, max: Int = Int.MAX_VALUE): String {
        val primitive = value[key] as? JsonPrimitive ?: invalid("SCHEMA_FIELD_INVALID")
        if (!primitive.isString) invalid("SCHEMA_FIELD_INVALID")
        return primitive.content.trim().also { if (it.length !in min..max) invalid("SCHEMA_FIELD_INVALID") }
    }

    private fun choice(value: JsonObject, key: String, allowed: Set<String>): String = text(value, key).also {
        if (it !in allowed) invalid("SCHEMA_ENUM_INVALID")
    }

    private fun optionalChoice(value: JsonObject, key: String, allowed: Set<String>): String? =
        if (value[key] == JsonNull) null else choice(value, key, allowed)

    private fun confidence(value: JsonObject, key: String) {
        if (value[key] == JsonNull) return
        val primitive = value[key] as? JsonPrimitive ?: invalid("CONFIDENCE_INVALID")
        if (primitive.isString || primitive.booleanOrNull != null) invalid("CONFIDENCE_INVALID")
        val number = primitive.doubleOrNull ?: invalid("CONFIDENCE_INVALID")
        if (!number.isFinite() || number !in 0.0..1.0) invalid("CONFIDENCE_INVALID")
    }

    private fun band(value: JsonObject, key: String): Int? {
        if (value[key] == JsonNull) return null
        val primitive = value[key] as? JsonPrimitive ?: invalid("BAND_INVALID")
        if (primitive.isString || primitive.booleanOrNull != null) invalid("BAND_INVALID")
        return primitive.intOrNull?.takeIf { it in 1..5 } ?: invalid("BAND_INVALID")
    }

    private fun array(value: JsonObject, key: String, min: Int = 0, max: Int): JsonArray {
        val elements = value[key] as? JsonArray ?: invalid("SCHEMA_ARRAY_INVALID")
        if (elements.size !in min..max) invalid("SCHEMA_ARRAY_INVALID")
        return elements
    }

    private fun strings(value: JsonObject, key: String, max: Int, allowed: Set<String>): List<String> =
        array(value, key, max = max).map { element ->
            val primitive = element as? JsonPrimitive ?: invalid("SCHEMA_FIELD_INVALID")
            if (!primitive.isString) invalid("SCHEMA_FIELD_INVALID")
            primitive.content.trim().also { if (it !in allowed) invalid("SCHEMA_ENUM_INVALID") }
        }

    private fun ids(value: JsonObject, key: String): List<String> {
        val elements = array(value, key, max = 12).map { element ->
            val primitive = element as? JsonPrimitive ?: invalid("EVIDENCE_INVALID")
            if (!primitive.isString) invalid("EVIDENCE_INVALID")
            primitive.content.trim().also { if (it.length !in 2..12) invalid("EVIDENCE_INVALID") }
        }
        if (elements.size != elements.toSet().size) invalid("EVIDENCE_DUPLICATE")
        return elements
    }

    private fun invalid(code: String): Nothing = throw WritingReviewProtocolException(code)
}

/** The IDs are derived from the actual draft fields, never accepted from the model. */
internal data class WritingDraftEvidence(
    val originText: String,
    val providedFacts: List<String>,
    val requiredIntents: List<String>,
    val responseConstraints: List<String>,
    val focusReason: String,
) {
    val orderedSegmentIds: List<String>
        get() = buildList {
            add("O1")
            providedFacts.indices.forEach { add("F${it + 1}") }
            requiredIntents.indices.forEach { add("I${it + 1}") }
            responseConstraints.indices.forEach { add("C${it + 1}") }
            add("N1")
        }
    val segmentIds: Set<String> get() = orderedSegmentIds.toSet()
}

/** Python binding_failure와 보정·복구 hash 확인 순서를 유지한다. */
internal object WritingReviewBinding {
    fun failure(
        review: WritingReview,
        draft: WritingDraftEvidence,
        candidateId: String,
        contentHash: String,
        revisionHash: String? = null,
        recoveryHash: String? = null,
    ): String? {
        if (review.candidateId != candidateId) return "VERIFIER_IDENTITY_MISMATCH"
        if (review.contentHash != contentHash) return "VERIFIER_CONTENT_HASH_MISMATCH"
        val ids = draft.segmentIds
        if ((review.difficultyEvidenceIds + review.checks.flatMap { it.evidenceIds }).any { it !in ids }) {
            return "VERIFIER_EVIDENCE_SEGMENT_INVALID"
        }
        if (review.demands?.any { check -> check.evidenceIds.any { it !in ids || it == "N1" } } == true) {
            return "VERIFIER_PRODUCTION_EVIDENCE_INVALID"
        }
        if ("N1" in review.difficultyEvidenceIds) return "VERIFIER_EVIDENCE_SCOPE_MISMATCH"
        for (check in review.checks) {
            if (check.status == "UNSURE") continue
            val evidence = check.evidenceIds.toSet()
            if (check.criterion == "NOTE_QUALITY" && "N1" !in evidence) return "VERIFIER_EVIDENCE_SCOPE_MISMATCH"
            if (check.criterion != "NOTE_QUALITY" && evidence.all { it == "N1" }) {
                return "VERIFIER_EVIDENCE_SCOPE_MISMATCH"
            }
            if (check.criterion == "ANSWER_LEAK" && check.status == "PASS" && "N1" !in evidence) {
                return "VERIFIER_EVIDENCE_SCOPE_MISMATCH"
            }
        }
        val preservation = review.preservation
        if (preservation != null && preservation.evidenceIds.any { it != "B0" && it !in ids || it == "N1" }) {
            return "VERIFIER_REVISION_EVIDENCE_INVALID"
        }
        if (revisionHash != null && review.revisionHash != revisionHash) return "VERIFIER_REVISION_HASH_MISMATCH"
        if (recoveryHash != null && review.recoveryHash != recoveryHash) return "VERIFIER_RECOVERY_HASH_MISMATCH"
        return null
    }
}

/** Python assess_acceptance/repaired_acceptance의 판정 순서를 유지한다. */
internal object WritingReviewAcceptance {
    fun decide(
        review: WritingReview, targetBand: Int, writingType: WritingType,
        adjudicated: Boolean = false, allowAdjacentRecheck: Boolean = true,
        repaired: Boolean = false,
    ): WritingAcceptance {
        if (!repaired && review.sourcePreservation != null) {
            // 일반 판정의 거부가 우선이다. 그 밖의 결과는 원문 의미 보존으로 다시 제한한다.
            val base = decide(
                review.copy(sourcePreservation = null), targetBand, writingType,
                adjudicated, allowAdjacentRecheck,
            )
            if (base.action == "REJECT") return base
            val source = review.sourcePreservation
            if (source.status == "FAIL") return WritingAcceptance("REJECT", "SOURCE_MEANING_${source.issues.first()}")
            if (source.status == "UNSURE") return if (adjudicated)
                WritingAcceptance("REJECT", "SOURCE_MEANING_UNRESOLVED")
            else WritingAcceptance("ADJUDICATE", "SOURCE_MEANING_UNSURE")
            return base
        }
        val finalAdjudication = adjudicated || repaired
        val adjacent = allowAdjacentRecheck && !repaired
        val hardIssue = review.issues.firstOrNull { it != "NOTE_ORIGIN_LANGUAGE" }
        if (hardIssue != null) return WritingAcceptance("REJECT", "QUALITY_$hardIssue")
        if (review.observedType !in setOf(writingType.name, "UNSURE")) return WritingAcceptance(
            "REJECT", "QUALITY_TASK_TYPE",
        )
        if (review.difficultyStatus == "ASSESSED" && review.estimatedBand != targetBand) {
            if (!finalAdjudication && adjacent && review.verdict == "PASS" && review.estimatedBand != null &&
                kotlin.math.abs(review.estimatedBand - targetBand) == 1
            ) {
                return WritingAcceptance("ADJUDICATE", "ADJACENT_BAND_RECHECK")
            }
            return WritingAcceptance("REJECT", "VERIFIED_BAND_MISMATCH")
        }
        if (review.difficultyStatus == "BORDERLINE" && targetBand !in setOf(
                review.estimatedBand, review.alternativeBand,
            )
        ) {
            return WritingAcceptance("REJECT", "VERIFIED_BAND_MISMATCH")
        }
        val uncertain = review.observedType == "UNSURE" || review.difficultyStatus != "ASSESSED" ||
            review.checks.any { it.status == "UNSURE" }
        if (uncertain) return if (finalAdjudication) WritingAcceptance("REJECT", "SEMANTIC_UNRESOLVED")
        else WritingAcceptance("ADJUDICATE", "EXPLICIT_UNCERTAINTY")
        if (review.estimatedBand == 5 && review.demands.orEmpty().any { it.gapCode != null }) {
            return WritingAcceptance("REJECT", "DIFFICULTY_EVIDENCE_CONFLICT")
        }
        if (review.issues == listOf("NOTE_ORIGIN_LANGUAGE")) return WritingAcceptance(
            "LOCALIZE_NOTE", "NOTE_ORIGIN_LANGUAGE",
        )
        val base = if (review.verdict == "PASS") WritingAcceptance("ACCEPT", "VERIFIED")
        else WritingAcceptance("REJECT", "SEMANTIC_UNRESOLVED")
        if (!repaired || base.action != "ACCEPT") return if (repaired && base.action != "ACCEPT")
            WritingAcceptance("REJECT", base.reason) else base
        val preservation = review.preservation ?: throw WritingReviewProtocolException("PRESERVATION_MISSING")
        if (preservation.status == "FAIL") return WritingAcceptance(
            "REJECT", "DIFFICULTY_REPAIR_${preservation.issues.first()}",
        )
        if (preservation.status == "UNSURE") return WritingAcceptance(
            "REJECT", "DIFFICULTY_REPAIR_PRESERVATION_UNRESOLVED",
        )
        if (review.demands == null || review.demands.any { it.status != "PRESENT" || it.gapCode != null }) {
            return WritingAcceptance("REJECT", "DIFFICULTY_REPAIR_EVIDENCE_CONFLICT")
        }
        return base
    }
}
