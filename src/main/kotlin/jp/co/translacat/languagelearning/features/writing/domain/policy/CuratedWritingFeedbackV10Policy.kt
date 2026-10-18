package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackV3Policy.Segment
import kotlinx.serialization.json.*

/** v8의 인용·권한 검사를 보존하면서 참고답안의 표현을 필수 조건으로 끌어오지 않는 판정 계약이다. */
internal object CuratedWritingFeedbackV10Policy {
    const val version = "curated-writing-feedback-v10"

    fun prompt(
        context: CuratedWritingFeedbackPolicy.Context,
        reference: JsonObject,
        answer: String,
        requirements: List<Pair<String, String>>,
        segments: List<Segment>,
    ): String {
        // 공개 원문·사실·조건·학습자 답안은 그대로 유지하고 모든 유형의 비권위 예시는 판정 입력에서 제외한다.
        val previous = Json.parseToJsonElement(
            CuratedWritingFeedbackPolicy.prompt(context, reference, answer, requirements, segments),
        ).jsonObject
        // 전체 범위의 끝과 필수 출처 목록을 명시한다. 원문 단위나 의미 판정·응답 schema는 바꾸지 않는다.
        val task = previous.getValue("task").jsonObject
        val coverage = buildJsonObject {
            put("requirementRanges", JsonArray(task.getValue("requirements").jsonArray.map { raw ->
                val requirement = raw.jsonObject
                val units = requirement.getValue("sourceUnits").jsonArray
                buildJsonObject {
                    put("requirementId", requirement.getValue("id"))
                    put("firstUnitId", units.first().jsonObject.getValue("id"))
                    put("lastUnitId", units.last().jsonObject.getValue("id"))
                    put("unitCount", units.size)
                }
            }))
            put("requiredTaskFactIds", JsonArray(CuratedWritingFeedbackPolicy.taskFacts(context).map { JsonPrimitive(it.first) }))
            put("requiredTaskConditionIds", JsonArray(CuratedWritingFeedbackPolicy.taskConditions(context).map { JsonPrimitive(it.first) }))
        }
        return JsonObject(previous - "referenceExamples" + mapOf(
            "judgmentCriteria" to CuratedWritingSemanticCriteria.contract(),
            "responseCoverage" to coverage,
        )).toString()
    }

    val instructions = CuratedWritingFeedbackPolicy.instructions + "\n\n" +
        CuratedWritingSemanticCriteria.instructions + "\n\n" + """
            Apply judgmentCriteria to every part and relation check. Reference examples are deliberately absent;
            publicPromptAndConditions, explicitTaskFacts and prescribedConditions retain their existing authority.
            Return exactly one uncertainties entry for each requirement containing an UNCERTAIN part, and none
            for other requirements. Its Japanese learningText must explain the two plausible readings and why
            they differ against the stated condition, not merely ask the learner to add more detail.
            Leave optional alternatives empty when no meaning-preserving useful change is needed.
            responseCoverage is a mechanical completion checklist, not additional task meaning. For each C,
            cover every source unit from firstUnitId through lastUnitId exactly once. Include the grammatical
            instruction ending in the final semantic part; do not stop at the last content noun or verb.
            Separate independent propositions when their evidence or status differs. Do not label a whole
            compound requirement CONFLICT or ABSENT when another independent part is PRESENT. The same T or P
            ID may link to multiple relevant parts when its supplied text contains multiple propositions.
            Every requiredTaskFactIds and requiredTaskConditionIds entry must occur in an appropriate part.
            T links do not replace P links: one part may need both. Never invent a link merely to fill the list.
            Check every evidence quote as an exact contiguous substring of its named learner segment. Do not
            insert ellipses, parenthetical explanations or reconstructed words into quotes. Missing evidence
            means ABSENT, not a fabricated quotation of an implied event.
            Before returning a patch, apply it once to the original complete segment and check the resulting
            sentence. Preserve text outside the patch and do not duplicate adjacent particles or endings.
            learningText must be Japanese only, including any quotation; never quote Korean task text there.
            Explain T/P corrections as alignment with the supplied assignment, not proof of the learner's life.
        """.trimIndent()

    fun schema(
        context: CuratedWritingFeedbackPolicy.Context,
        requirements: List<Pair<String, String>>,
        segments: List<Segment>,
    ): JsonObject = CuratedWritingFeedbackPolicy.schema(context, requirements, segments)

    fun validate(
        output: JsonElement,
        context: CuratedWritingFeedbackPolicy.Context,
        requirements: List<Pair<String, String>>,
        segments: List<Segment>,
    ): JsonObject = validateWithRepairRanges(output, context, requirements, segments, emptyList())

    fun validateWithRepairRanges(output: JsonElement, context: CuratedWritingFeedbackPolicy.Context,
                                requirements: List<Pair<String, String>>, segments: List<Segment>,
                                repairRanges: List<CuratedWritingRepairRangeProof>): JsonObject {
        // 원문 coverage·실제 인용·교정 권한·선택 조언 검사를 먼저 적용한다. 잘못된 판정을 사후 삭제하지 않는다.
        val checked = CuratedWritingFeedbackPolicy.validateWithRepairRanges(output, context, requirements, segments, repairRanges)
        try {
            val uncertainIds = checked.getValue("observations").jsonArray.map { it.jsonObject }
                .filter { row -> row.getValue("requirementParts").jsonArray.any {
                    it.jsonObject.getValue("status").jsonPrimitive.content == "UNCERTAIN"
                } }.map { it.getValue("requirementId").jsonPrimitive.content }.toSet()
            val explanations = checked.getValue("uncertainties").jsonArray.map {
                it.jsonObject.getValue("requirementId").jsonPrimitive.content
            }
            require(explanations.size == explanations.distinct().size && explanations.toSet() == uncertainIds)
            return JsonObject(checked + ("policyVersion" to JsonPrimitive(version)))
        } catch (_: Exception) {
            throw IllegalArgumentException("WRITING_FEEDBACK_SCHEMA_INVALID")
        }
    }
}
