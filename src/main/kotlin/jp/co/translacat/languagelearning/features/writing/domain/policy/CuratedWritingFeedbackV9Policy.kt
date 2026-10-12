package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackV3Policy.Segment
import kotlinx.serialization.json.*

/** 모델은 판단과 근거를 제출하고 서버는 원문 범위, 권한 연결, 관계 요약과 교정 patch를 조립한다. */
internal object CuratedWritingFeedbackV9Policy {
    const val version = "curated-writing-feedback-v9"
    private val relationKeys = listOf("causality", "chronology", "specificity", "stance")
    private val partStates = listOf("PRESENT", "ABSENT", "CONFLICT", "UNCERTAIN")
    private val relationKinds = listOf("NONE") + relationKeys

    fun prompt(
        context: CuratedWritingFeedbackPolicy.Context,
        reference: JsonObject,
        answer: String,
        requirements: List<Pair<String, String>>,
        segments: List<Segment>,
    ): String = CuratedWritingFeedbackPolicy.prompt(context, reference, answer, requirements, segments)

    val instructions = """
        Give formative writing feedback only. Do not score, assign a band, profile, or weakness tag.
        The server owns every requirement's Korean source text and its sourceUnits. Return one observation
        under every supplied C key. firstPart begins at its first unit. Each followingPart gives ONLY the
        startUnitId of the next distinct meaning; the server derives all ends, including the last unit.
        Split independent facts, reasons, requests, choices and conditions. An unsplit part must judge its
        entire meaning. Do not merge a present request with a missing reason to call both absent or present.
        PRESENT needs exact learner evidence, ABSENT has no evidence, CONFLICT needs an actual expressed
        contradiction, and UNCERTAIN needs genuinely ambiguous learner evidence. A missing personal choice
        or reason is ABSENT, never a contradiction. For CONFLICT/UNCERTAIN select the affected relationKind;
        use NONE for PRESENT/ABSENT. Do not output requirement text, end positions or relation summaries.
        Independently assess each explicitTaskFact T and GUIDED prescribedCondition P exactly once under
        authorityAssignments. Point its requirementId and sourceUnitId at the relevant part. T is a supplied
        fact; P is an explicit assignment condition. FREE and TRANSLATION have no P authority. Assignment
        instructions asking for personal preferences do not prescribe which preference the learner has.
        The learner answer alone establishes personal experiences, choices and reasons. Reference answers
        are examples, not requirements. Keep original causes, order, quantities, specificity and stance.
        Only propose a necessary correction for an actual spelling, inflection or syntax error or an
        expressed contradiction of a supplied T/P authority. A missing detail needs a supplement request,
        not an invented replacement. Valid alternative phrasing and optional duration wording are not errors.
        Give authorityId as empty for LANGUAGE_FORM, T ID for a supplied fact conflict, or P ID for an
        explicitly prescribed condition conflict. Use formReason SPELLING/INFLECTION/SYNTAX only when
        authorityId is empty, otherwise NONE. The server derives the issue type and checks the quoted part.
        Return a FULL corrected learner segment in replacementSegment, preserving all unaffected words;
        the server computes the single local patch and rejects edits outside the cited evidence. Do the
        same for optional alternatives. An optional EXPRESSION_ONLY suggestion must preserve the learner's
        facts, meaning and valid style. HYPOTHETICAL_EXAMPLE must be clearly conditional, never a learner
        fact or a completed requirement. Do not invent a personal reason, date, circumstance or certainty.
        Explain the specific edit in Japanese learningText. Do not write Korean explanation text; the server
        produces it. Quote actual learner text exactly, with the smallest sufficient evidence.
    """.trimIndent()

    fun schema(
        context: CuratedWritingFeedbackPolicy.Context,
        requirements: List<Pair<String, String>>,
        segments: List<Segment>,
    ): JsonObject {
        fun text(values: List<String>? = null) = buildJsonObject {
            put("type", "string")
            values?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
        }
        fun obj(fields: Map<String, JsonElement>) = buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(fields))
            put("additionalProperties", false)
            put("required", JsonArray(fields.keys.map(::JsonPrimitive)))
        }
        fun list(item: JsonElement) = buildJsonObject { put("type", "array"); put("items", item) }

        // 고정된 C/T/P 키를 schema의 필수 속성으로 만들어 ID 누락과 중복 작성을 없앤다.
        val ids = requirements.map { it.first }
        val authorityIds = CuratedWritingFeedbackPolicy.taskFacts(context).map { it.first } +
            CuratedWritingFeedbackPolicy.taskConditions(context).map { it.first }
        val segmentIds = segments.map { it.id }
        val evidence = obj(mapOf("segmentId" to text(segmentIds), "quote" to text()))
        val partFields = mapOf("status" to text(partStates), "evidence" to list(evidence),
            "relationKind" to text(relationKinds))
        val observation = obj(mapOf(
            "firstPart" to obj(partFields),
            "followingParts" to list(obj(mapOf("startUnitId" to text()) + partFields)),
        ))
        val authority = obj(mapOf("requirementId" to text(ids), "sourceUnitId" to text()))
        val correction = obj(mapOf(
            "requirementId" to text(ids), "segmentId" to text(segmentIds),
            "replacementSegment" to text(), "authorityId" to text(listOf("") + authorityIds),
            "formReason" to text(listOf("SPELLING", "INFLECTION", "SYNTAX", "NONE")),
            "learningText" to text(),
        ))
        val alternative = obj(mapOf(
            "requirementId" to text(ids), "suggestionKind" to text(listOf("EXPRESSION_ONLY", "HYPOTHETICAL_EXAMPLE")),
            "segmentId" to text(segmentIds), "replacementSegment" to text(), "learningText" to text(),
        ))
        return obj(mapOf(
            "observations" to obj(ids.associateWith { observation }),
            "authorityAssignments" to obj(authorityIds.associateWith { authority }),
            "necessaryCorrections" to list(correction),
            "optionalAlternatives" to list(alternative),
            "uncertainties" to list(obj(mapOf("requirementId" to text(ids), "learningText" to text()))),
        ))
    }

    fun validate(
        output: JsonElement,
        context: CuratedWritingFeedbackPolicy.Context,
        requirements: List<Pair<String, String>>,
        segments: List<Segment>,
    ): JsonObject {
        try {
            val root = output.jsonObject
            require(root.keys == setOf("observations", "authorityAssignments", "necessaryCorrections", "optionalAlternatives", "uncertainties"))
            val requirementTexts = requirements.toMap()
            val observations = root.getValue("observations").jsonObject
            require(observations.keys == requirementTexts.keys)
            val units = requirements.associate { (id, text) -> id to CuratedWritingRequirementUnits.compile(id, text) }
            val positions = units.mapValues { (_, values) -> values.mapIndexed { index, value -> value.id to index }.toMap() }
            val facts = CuratedWritingFeedbackPolicy.taskFacts(context).map { it.first }
            val conditions = CuratedWritingFeedbackPolicy.taskConditions(context).map { it.first }

            // 각 C의 첫 시작과 끝을 서버가 확정한다. 모델은 실제 의미 변화가 있는 중간 시작만 지정한다.
            val partsByRequirement = requirements.associate { (id, _) ->
                val row = observations.getValue(id).jsonObject
                require(row.keys == setOf("firstPart", "followingParts"))
                val first = row.getValue("firstPart").jsonObject
                val following = row.getValue("followingParts").jsonArray.map { it.jsonObject }
                val starts = listOf(0) + following.map { part ->
                    positions.getValue(id)[part.string("startUnitId")] ?: error("WRITING_SOURCE_UNIT_UNKNOWN")
                }
                require(starts.zipWithNext().all { (a, b) -> b > a && b < units.getValue(id).size })
                val decisions = listOf(first) + following
                val mapped = decisions.mapIndexed { index, decision ->
                    val expected = if (index == 0) setOf("status", "evidence", "relationKind") else
                        setOf("startUnitId", "status", "evidence", "relationKind")
                    require(decision.keys == expected)
                    val status = decision.string("status")
                    val kind = decision.string("relationKind")
                    require(status in partStates && kind in relationKinds)
                    require((status in setOf("CONFLICT", "UNCERTAIN")) == (kind != "NONE"))
                    val evidence = decision.getValue("evidence").jsonArray
                    require((status == "ABSENT") == evidence.isEmpty())
                    buildJsonObject {
                        put("startUnitId", units.getValue(id)[starts[index]].id)
                        put("endUnitId", units.getValue(id)[(starts.getOrNull(index + 1) ?: units.getValue(id).size) - 1].id)
                        put("status", status)
                        put("evidence", evidence)
                        put("taskFactIds", JsonArray(emptyList()))
                        put("taskConditionIds", JsonArray(emptyList()))
                    }
                }
                id to Triple(starts, decisions, mapped)
            }

            // T/P는 각각 schema의 필수 키로 받는다. 유효한 위치의 한 부분에 서버가 ID를 붙인다.
            val assignments = root.getValue("authorityAssignments").jsonObject
            require(assignments.keys == (facts + conditions).toSet())
            val attached = mutableMapOf<Pair<String, Int>, MutableList<String>>()
            assignments.forEach { (authorityId, raw) ->
                val assignment = raw.jsonObject
                require(assignment.keys == setOf("requirementId", "sourceUnitId"))
                val id = assignment.string("requirementId")
                val sourcePosition = positions.getValue(id)[assignment.string("sourceUnitId")]
                    ?: error("WRITING_AUTHORITY_SOURCE_UNIT_UNKNOWN")
                val starts = partsByRequirement.getValue(id).first
                val partIndex = starts.indexOfLast { it <= sourcePosition }
                require(partIndex >= 0)
                attached.getOrPut(id to partIndex) { mutableListOf() } += authorityId
            }
            val relationsByRequirement = mutableMapOf<String, JsonObject>()
            val assembledObservations = requirements.map { (id, _) ->
                val (_, decisions, mapped) = partsByRequirement.getValue(id)
                val values = relationKeys.associateWith { key ->
                    if (decisions.all { it.string("status") == "ABSENT" }) "NOT_APPLICABLE" else "PRESERVED"
                }.toMutableMap()
                decisions.forEach { part ->
                    if (part.string("status") in setOf("CONFLICT", "UNCERTAIN")) {
                        val key = part.string("relationKind")
                        val state = part.string("status")
                        require(values.getValue(key) !in setOf("CONFLICT", "UNCERTAIN") || values.getValue(key) == state)
                        values[key] = state
                    }
                }
                val relationChecks = JsonObject(values.mapValues { JsonPrimitive(it.value) })
                relationsByRequirement[id] = relationChecks
                buildJsonObject {
                    put("requirementId", id)
                    put("requirementParts", JsonArray(mapped.mapIndexed { index, part ->
                        val ids = attached[id to index].orEmpty()
                        JsonObject(part + mapOf(
                            "taskFactIds" to JsonArray(ids.filter { it.startsWith("T") }.map(::JsonPrimitive)),
                            "taskConditionIds" to JsonArray(ids.filter { it.startsWith("P") }.map(::JsonPrimitive)),
                        ))
                    }))
                    put("relationChecks", relationChecks)
                }
            }

            // 모델의 완성 문장에서 한 군데 차이만 추출한다. 최종 권한·인용·중복 검사는 보존한 v8 계약이 수행한다.
            val knownSegments = segments.associateBy(Segment::id)
            val corrections = root.getValue("necessaryCorrections").jsonArray.map { raw ->
                val row = raw.jsonObject
                require(row.keys == setOf("requirementId", "segmentId", "replacementSegment", "authorityId", "formReason", "learningText"))
                val authorityId = row.string("authorityId")
                val formReason = row.string("formReason")
                require((authorityId.isEmpty() && formReason in setOf("SPELLING", "INFLECTION", "SYNTAX")) ||
                    (authorityId in assignments && authorityId.isNotEmpty() && formReason == "NONE"))
                val issueType = when {
                    authorityId.isEmpty() -> "LANGUAGE_FORM"
                    authorityId.startsWith("T") -> "TASK_FACT_CONFLICT"
                    else -> "TASK_CONDITION_CONFLICT"
                }
                JsonObject(row - setOf("replacementSegment", "authorityId") + mapOf(
                    "patch" to localPatch(knownSegments.getValue(row.string("segmentId")).text, row.string("replacementSegment")),
                    "issueType" to JsonPrimitive(issueType),
                    "sourceFactId" to JsonPrimitive(if (authorityId.startsWith("T")) authorityId else ""),
                    "sourceAuthorityId" to JsonPrimitive(if (authorityId.startsWith("P")) authorityId else ""),
                ))
            }
            val alternativeRanges = mutableMapOf<String, MutableList<IntRange>>()
            val alternatives = root.getValue("optionalAlternatives").jsonArray.map { raw ->
                val row = raw.jsonObject
                require(row.keys == setOf("requirementId", "suggestionKind", "segmentId", "replacementSegment", "learningText"))
                val segmentId = row.string("segmentId")
                val originalSegment = knownSegments.getValue(segmentId).text
                val patch = localPatch(originalSegment, row.string("replacementSegment"))
                val originalSpan = patch.string("original")
                val start = originalSegment.indexOf(originalSpan)
                val range = start until start + originalSpan.length
                val occupied = alternativeRanges.getOrPut(segmentId) { mutableListOf() }
                // 같은 문장의 같은 범위를 여러 선택 제안으로 반복해 사용자에게 중복 표시하지 않는다.
                require(occupied.none { it.first <= range.last && range.first <= it.last })
                occupied += range
                JsonObject(row - "replacementSegment" + mapOf(
                    "patch" to patch,
                    "relationChecks" to relationsByRequirement.getValue(row.string("requirementId")),
                ))
            }
            val checked = CuratedWritingFeedbackPolicy.validate(buildJsonObject {
                put("observations", JsonArray(assembledObservations))
                put("necessaryCorrections", JsonArray(corrections))
                put("optionalAlternatives", JsonArray(alternatives))
                put("uncertainties", root.getValue("uncertainties"))
            }, context, requirements, segments)
            return JsonObject(checked + ("policyVersion" to JsonPrimitive(version)))
        } catch (_: Exception) {
            throw IllegalArgumentException("WRITING_FEEDBACK_SCHEMA_INVALID")
        }
    }

    private fun localPatch(original: String, replacement: String): JsonObject {
        require(replacement.isNotBlank() && original != replacement)
        var start = 0
        while (start < minOf(original.length, replacement.length) && original[start] == replacement[start]) start++
        var endOriginal = original.length
        var endReplacement = replacement.length
        while (endOriginal > start && endReplacement > start && original[endOriginal - 1] == replacement[endReplacement - 1]) {
            endOriginal--
            endReplacement--
        }
        if (endOriginal == start) {
            if (start > 0) start-- else { endOriginal++; endReplacement++ }
        }
        var from = original.substring(start, endOriginal)
        while (original.indexOf(from, original.indexOf(from) + 1) >= 0 && start > 0) {
            start--
            from = original.substring(start, endOriginal)
        }
        while (original.indexOf(from, original.indexOf(from) + 1) >= 0 && endOriginal < original.length) {
            endOriginal++
            endReplacement++
            from = original.substring(start, endOriginal)
        }
        require(from.isNotBlank() && original.indexOf(from, original.indexOf(from) + 1) < 0)
        val to = replacement.substring(start, endReplacement)
        require(original.replaceRange(start, endOriginal, to) == replacement)
        return buildJsonObject { put("original", from); put("replacement", to) }
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
