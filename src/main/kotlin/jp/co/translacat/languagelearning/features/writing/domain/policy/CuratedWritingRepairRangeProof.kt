package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*

/** 실제 연속 source와 고정 atom에서만 발급한다. 모델 JSON을 proof로 역직렬화하는 경로는 없다. */
internal class CuratedWritingRepairRangeProof private constructor(
    val atomId: String,
    val requirementId: String,
    val segmentId: String,
    val start: Int,
    val end: Int,
    val original: String,
    private val context: CuratedWritingFeedbackPolicy.Context,
    private val requirements: List<Pair<String, String>>,
    private val segments: List<CuratedWritingFeedbackV3Policy.Segment>,
    private val slices: List<Slice>,
    private val authorities: Set<Pair<String, String>>,
    private val sourceRevisionHash: String,
    private val verified: JsonObject,
) {
    private data class Slice(val id: String, val start: Int, val end: Int, val text: String)

    fun requireContext(actual: CuratedWritingFeedbackPolicy.Context,
                       actualRequirements: List<Pair<String, String>>,
                       actualSegments: List<CuratedWritingFeedbackV3Policy.Segment>) {
        require(context == actual && requirements == actualRequirements && segments == actualSegments) {
            "WRITING_FEEDBACK_RANGE_PROOF_CONTEXT_MISMATCH"
        }
    }

    fun requireBound(plan: CuratedWritingFeedbackV13Policy.Plan, actualVerified: JsonObject) {
        requireContext(plan.context, plan.requirements, plan.segments)
        require(sourceRevisionHash == CuratedWritingEvidenceV14.sourceRevisionHash(plan) && verified == actualVerified) {
            "WRITING_FEEDBACK_RANGE_PROOF_REVISION_MISMATCH"
        }
    }

    fun permits(id: String, segment: String, patch: JsonObject, part: JsonObject,
                issueType: String? = null, authority: String? = null): Boolean {
        if (id != requirementId || segment != segmentId || patch["original"]?.jsonPrimitive?.content != original) return false
        if (part["status"]?.jsonPrimitive?.content != "CONFLICT") return false
        if (issueType != null && (issueType to authority.orEmpty()) !in authorities) return false
        val evidence = part["evidence"]?.jsonArray ?: return false
        // source 인용은 원본 각각 그대로다. 합친 quote나 새 ID를 만들어 containment를 만족시키지 않는다.
        return evidence.size == slices.size && slices.all { slice -> evidence.count { raw ->
            raw.jsonObject["segmentId"]?.jsonPrimitive?.content == segmentId &&
                raw.jsonObject["quote"]?.jsonPrimitive?.content == slice.text
        } == 1 }
    }

    fun covers(atom: String, segment: String, patch: JsonObject, issueType: String, authority: String): Boolean =
        atom == atomId && segment == segmentId && patch["original"]?.jsonPrimitive?.content == original &&
            (issueType to authority) in authorities

    fun promptJson(): JsonObject = buildJsonObject {
        put("version", "contiguous-source-range-proof-v1")
        put("sourceRevisionHash", sourceRevisionHash); put("atomId", atomId)
        put("requirementId", requirementId); put("segmentId", segmentId); put("start", start); put("end", end)
        put("sourceSlices", JsonArray(slices.map { slice -> buildJsonObject {
            put("sourceId", slice.id); put("start", slice.start); put("end", slice.end); put("text", slice.text)
        } }))
    }

    companion object {
        fun create(plan: CuratedWritingFeedbackV13Policy.Plan, verified: JsonObject,
                   atom: CuratedWritingMeaningContract.Atom): CuratedWritingRepairRangeProof? {
            val decisions = CuratedWritingFeedbackV13Policy.resolvedAtoms(plan, verified)
            val decision = decisions.getValue(atom.id)
            if (atom.alternatives.isNotEmpty()) return null
            val raw = verified.getValue("atoms").jsonObject.getValue(atom.id).jsonObject
            if (decision.state != "CONFLICT" || decision.sources.size < 2 ||
                raw.getValue("recipient").jsonPrimitive.content == "EVALUATOR" ||
                decision.kind !in setOf("EVENT", "STATE")) return null
            val selected = plan.sources.filter { it.id in decision.sources }
            if (selected.map { it.segmentId }.distinct().size != 1) return null
            val segment = plan.segments.single { it.id == selected.first().segmentId }
            val segmentSources = plan.sources.filter { it.segmentId == segment.id }
            var cursor = 0
            val positions = segmentSources.associate { source ->
                val slice = Slice(source.id, cursor, cursor + source.text.length, source.text)
                cursor += source.text.length
                source.id to slice
            }
            require(cursor == segment.text.length && segmentSources.joinToString("") { it.text } == segment.text)
            val slices = selected.map { positions.getValue(it.id) }.sortedBy { it.start }
            if (slices.zipWithNext().any { (left, right) -> left.end != right.start }) return null
            if (decisions.any { (other, value) -> other != atom.id && value.sources.any { it in decision.sources } }) return null
            val start = slices.first().start
            val end = slices.last().end
            if (!boundary(segment.text, start) || !boundary(segment.text, end)) return null
            val original = segment.text.substring(start, end)
            if (segment.text.indexOf(original) != start || segment.text.lastIndexOf(original) != start) return null
            val authorities = atom.taskFactIds.map { "TASK_FACT_CONFLICT" to it } +
                if (plan.context.writingType == jp.co.translacat.languagelearning.features.writing.domain.model.WritingType.GUIDED)
                    atom.taskConditionIds.map { "TASK_CONDITION_CONFLICT" to it } else emptyList()
            if (authorities.isEmpty()) return null
            return CuratedWritingRepairRangeProof(atom.id,
                plan.contract.requirements.single { requirement -> requirement.atoms.any { it.id == atom.id } }.id,
                segment.id, start, end, original, plan.context, plan.requirements.toList(), plan.segments.toList(),
                slices, authorities.toSet(), CuratedWritingEvidenceV14.sourceRevisionHash(plan), verified)
        }

        private fun boundary(text: String, index: Int): Boolean = index !in 1 until text.length ||
            !(text[index - 1].isHighSurrogate() && text[index].isLowSurrogate())
    }
}
