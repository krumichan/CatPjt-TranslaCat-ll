package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** 수리의 위치·목표·권한은 서버가 소유한다. 요구별 coverage와 물리적인 수정 한 건을 구분한다. */
internal object CuratedWritingRepairPlan {
    const val version = "server-repair-plan-v1"

    data class Coverage(val requirementId: String, val atomId: String, val issueType: String, val authorityId: String)

    data class Task(
        val id: String,
        val kind: String,
        val segmentId: String,
        val start: Int,
        val end: Int,
        val original: String,
        val target: String,
        val coverage: List<Coverage>,
        val formReason: String = "NONE",
        val factKey: String? = null,
        val deterministicReplacement: String? = null,
        val grammarFindings: JsonArray = JsonArray(emptyList()),
        val originalSegment: String = original,
        val rangeProof: CuratedWritingRepairRangeProof? = null,
    ) {
        val primary: Coverage get() = coverage.first()
    }

    data class Plan(
        val sourceRevisionHash: String,
        val verificationHash: String,
        val tasks: List<Task>,
        val blockers: List<String>,
        val hash: String,
    ) {
        val modelTaskIds: List<String> get() = tasks.filter { it.deterministicReplacement == null }.map { it.id }
        fun task(id: String): Task = tasks.single { it.id == id }
    }

    fun create(plan: CuratedWritingFeedbackV13Policy.Plan, verified: JsonObject): Plan {
        CuratedWritingFeedbackV13Policy.validateVerification(verified, plan)
        val sourceRevision = CuratedWritingEvidenceV14.sourceRevisionHash(plan)
        val verifiedHash = digest(verified.toString())
        val decisions = CuratedWritingFeedbackV13Policy.resolvedAtoms(plan, verified)
        val sourceOffsets = sourceOffsets(plan)
        val tasks = mutableListOf<Task>()
        val blockers = mutableListOf<String>()
        val factCoveredAtoms = mutableSetOf<String>()
        fun requirement(atomId: String) = plan.contract.requirements.single { row -> row.atoms.any { it.id == atomId } }.id
        fun authority(atom: CuratedWritingMeaningContract.Atom): List<Coverage> =
            atom.taskFactIds.map { Coverage(requirement(atom.id), atom.id, "TASK_FACT_CONFLICT", it) } +
                if (plan.context.writingType == WritingType.GUIDED) {
                    atom.taskConditionIds.map { Coverage(requirement(atom.id), atom.id, "TASK_CONDITION_CONFLICT", it) }
                } else emptyList()

        // 같은 사실 슬롯의 같은 원문 위치는 C1/C2가 함께 참조해도 한 번만 교정한다.
        plan.contract.facts.forEach { fact ->
            val binding = verified.getValue("facts").jsonObject.getValue(fact.key).jsonObject
            if (binding.string("slotRelation") != "MATCHED_EVENT") return@forEach
            val token = binding.string("valueToken")
            if (fact.normalizedAlternatives.getValue(token) == fact.expectedValue) return@forEach
            val source = plan.sources.single { it.id == binding.string("sourceId") }
            val localStart = source.text.indexOf(token)
            if (localStart < 0 || source.text.indexOf(token, localStart + 1) >= 0) {
                blockers += "AMBIGUOUS_FACT_OCCURRENCE:${fact.key}"
                return@forEach
            }
            val atomIds = fact.atomIds.filter { decisions.getValue(it).state == "CONFLICT" }
            val coverage = atomIds.map { id ->
                Coverage(requirement(id), id,
                    if (fact.sourceAuthorityId.startsWith("T")) "TASK_FACT_CONFLICT" else "TASK_CONDITION_CONFLICT",
                    fact.sourceAuthorityId)
            }
            if (coverage.isEmpty()) return@forEach
            val start = sourceOffsets.getValue(source.id) + localStart
            val anchor = uniqueAnchor(plan, source, start, start + token.length)
            if (anchor == null) {
                blockers += "NON_UNIQUE_FACT_PATCH:${fact.key}"
                return@forEach
            }
            val expectedToken = deterministicToken(fact)
            val replacement = expectedToken?.let {
                anchor.text.replaceRange(start - anchor.start, start - anchor.start + token.length, it)
            }
            tasks += Task("", "FACT", source.segmentId, anchor.start, anchor.end, anchor.text,
                "${fact.slot} = ${fact.expectedValue}", coverage, factKey = fact.key,
                deterministicReplacement = replacement,
                originalSegment = plan.segments.single { it.id == source.segmentId }.text)
            factCoveredAtoms += atomIds
        }

        // 고정 사실로 표현하지 못하는 자연어 충돌도 같은 원자·실제 위치의 T/P를 한 작업으로 묶는다.
        plan.contract.atoms.forEach { atom ->
            val decision = decisions.getValue(atom.id)
            if (decision.state != "CONFLICT" || atom.id in factCoveredAtoms) return@forEach
            val coverage = authority(atom)
            if (coverage.isEmpty()) return@forEach
            if (decision.sources.size != 1) {
                val proof = CuratedWritingRepairRangeProof.create(plan, verified, atom)
                if (proof == null) blockers += "AMBIGUOUS_NATURAL_CONFLICT_LOCATION:${atom.id}"
                else tasks += Task("", "NATURAL_FACT", proof.segmentId, proof.start, proof.end,
                    proof.original, atom.criterion, coverage,
                    originalSegment = plan.segments.single { it.id == proof.segmentId }.text, rangeProof = proof)
                return@forEach
            }
            val source = plan.sources.single { it.id == decision.sources.single() }
            val segment = plan.segments.single { it.id == source.segmentId }
            val start = sourceOffsets.getValue(source.id)
            if (segment.text.indexOf(source.text) != start || segment.text.lastIndexOf(source.text) != start) {
                blockers += "NON_UNIQUE_NATURAL_PATCH:${atom.id}"
                return@forEach
            }
            tasks += Task("", "NATURAL_FACT", source.segmentId, start, start + source.text.length,
                source.text, atom.criterion, coverage, originalSegment = segment.text)
        }

        // 문법은 독립 검사의 실제 errorCore를 기준으로 한다. 반복되는 말은 유일한 최소 문맥으로 고정한다.
        verified.getValue(CuratedWritingLanguageFormEvidence.key).jsonArray.forEachIndexed { index, raw ->
            val finding = raw.jsonObject
            val segment = plan.segments.single { it.id == finding.string("segmentId") }
            val quote = finding.string("quote")
            val core = finding.string("errorCore")
            val start = segment.text.indexOf(quote) + quote.indexOf(core)
            val end = start + core.length
            val sources = plan.sources.filter { source ->
                val offset = sourceOffsets.getValue(source.id)
                source.segmentId == segment.id && offset <= start && end <= offset + source.text.length
            }
            val coverage = plan.contract.requirements.filter { requirement ->
                requirement.atoms.any { atom -> decisions.getValue(atom.id).sources.any { id -> sources.any { it.id == id } } }
            }.map { requirement ->
                val atom = requirement.atoms.first { candidate ->
                    decisions.getValue(candidate.id).sources.any { id -> sources.any { it.id == id } }
                }
                Coverage(requirement.id, atom.id, "LANGUAGE_FORM", "")
            }
            val anchor = sources.firstNotNullOfOrNull { uniqueAnchor(plan, it, start, end) }
            if (coverage.isEmpty() || anchor == null) {
                blockers += "UNLINKED_LANGUAGE_FORM_LOCATION:${index + 1}"
                return@forEachIndexed
            }
            tasks += Task("", "LANGUAGE_FORM", segment.id, anchor.start, anchor.end, anchor.text,
                "Repair the independently observed ${finding.string("formReason")} at $start..$end; preserve all other meaning.",
                coverage, finding.string("formReason"), grammarFindings = JsonArray(listOf(finding)), originalSegment = segment.text)
        }

        // 서로 다른 오류나 서로 다른 목표를 겹침만으로 합치지 않는다. 현재 불겹침 계약에서 명시적으로 차단한다.
        val numbered = tasks.sortedWith(compareBy<Task> { plan.segments.indexOfFirst { segment -> segment.id == it.segmentId } }
            .thenBy { it.start }.thenBy { it.end }.thenBy { it.kind }).mapIndexed { index, task -> task.copy(id = "R${index + 1}") }
        numbered.forEachIndexed { index, task ->
            numbered.drop(index + 1).filter { other ->
                task.segmentId == other.segmentId && task.start < other.end && other.start < task.end
            }.forEach { other -> blockers += "OVERLAPPING_DISTINCT_REPAIRS:${task.id}:${other.id}" }
        }
        val identity = buildJsonObject {
            put("version", version); put("sourceRevisionHash", sourceRevision); put("verificationHash", verifiedHash)
            put("tasks", JsonArray(numbered.map(::taskJson)))
            put("blockers", JsonArray(blockers.distinct().map(::JsonPrimitive)))
        }
        return Plan(sourceRevision, verifiedHash, numbered, blockers.distinct(), digest(identity.toString()))
    }

    fun requireBound(plan: CuratedWritingFeedbackV13Policy.Plan, verified: JsonObject, repairs: Plan) {
        require(repairs.sourceRevisionHash == CuratedWritingEvidenceV14.sourceRevisionHash(plan) &&
            repairs.verificationHash == digest(verified.toString())) { "WRITING_FEEDBACK_REPAIR_REVISION_MISMATCH" }
        require(repairs.blockers.isEmpty()) { "WRITING_FEEDBACK_REPAIR_PLAN_BLOCKED:${repairs.blockers.joinToString(",")}" }
        repairs.tasks.forEach { task ->
            val segment = plan.segments.single { it.id == task.segmentId }
            require(task.originalSegment == segment.text && task.start >= 0 && task.start < task.end &&
                task.end <= segment.text.length && segment.text.substring(task.start, task.end) == task.original) {
                "WRITING_FEEDBACK_REPAIR_RANGE_MISMATCH"
            }
            task.rangeProof?.requireBound(plan, verified)
        }
    }

    fun taskJson(task: Task): JsonObject = buildJsonObject {
        put("id", task.id); put("kind", task.kind); put("segmentId", task.segmentId)
        put("start", task.start); put("end", task.end); put("original", task.original); put("target", task.target)
        put("originalSegment", task.originalSegment)
        put("sourceRangeProof", task.rangeProof?.promptJson() ?: JsonNull)
        put("formReason", task.formReason); put("factKey", task.factKey?.let(::JsonPrimitive) ?: JsonNull)
        put("deterministicReplacement", task.deterministicReplacement?.let(::JsonPrimitive) ?: JsonNull)
        put("coverage", JsonArray(task.coverage.map { row -> buildJsonObject {
            put("requirementId", row.requirementId); put("atomId", row.atomId)
            put("issueType", row.issueType); put("authorityId", row.authorityId)
        } }))
    }

    private data class Anchor(val start: Int, val end: Int, val text: String)

    private fun uniqueAnchor(plan: CuratedWritingFeedbackV13Policy.Plan, source: CuratedWritingFeedbackV13Policy.Source,
                             coreStart: Int, coreEnd: Int): Anchor? {
        val segment = plan.segments.single { it.id == source.segmentId }
        val offset = sourceOffsets(plan).getValue(source.id)
        for (radius in 0..source.text.length) {
            val start = maxOf(offset, coreStart - radius)
            val end = minOf(offset + source.text.length, coreEnd + radius)
            if (!boundary(segment.text, start) || !boundary(segment.text, end)) continue
            val text = segment.text.substring(start, end)
            if (text.isNotBlank() && segment.text.indexOf(text) == start && segment.text.lastIndexOf(text) == start) {
                return Anchor(start, end, text)
            }
        }
        return null
    }

    private fun sourceOffsets(plan: CuratedWritingFeedbackV13Policy.Plan): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        plan.sources.groupBy { it.segmentId }.forEach { (segmentId, sources) ->
            var offset = 0
            sources.forEach { source -> result[source.id] = offset; offset += source.text.length }
            require(sources.joinToString("") { it.text } == plan.segments.single { it.id == segmentId }.text)
        }
        return result
    }

    private fun deterministicToken(fact: CuratedWritingMeaningContract.FactConstraint): String? {
        val value = fact.expectedValue
        val candidates = fact.normalizedAlternatives.filterValues { it == value }.keys
        return when {
            value.matches(Regex("\\d{2}:\\d{2}")) -> candidates.filter { it.matches(Regex("\\d{1,2}時")) }.minByOrNull { it.length }
            value.matches(Regex("\\d{2}-\\d{2}")) -> candidates.filter { it.contains("月") && it.contains("日") }.minByOrNull { it.length }
            value == "MORNING" -> candidates.firstOrNull { it == "午前" }
            value == "AFTERNOON" -> candidates.firstOrNull { it == "午後" }
            else -> null
        }
    }

    private fun boundary(text: String, offset: Int): Boolean = offset == 0 || offset == text.length ||
        !(Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset]))

    fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
