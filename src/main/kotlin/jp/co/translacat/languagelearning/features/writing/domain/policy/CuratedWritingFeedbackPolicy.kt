package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*

/** 비점수 참고학습의 원문 인용은 모델 문장이 아니라 서버가 부여한 segment ID로 복원한다. */
internal object CuratedWritingFeedbackPolicy {
    const val version = "curated-writing-feedback-v1"

    data class Segment(val id: String, val text: String)

    fun segments(answer: String): List<Segment> {
        val pieces = answer.split(Regex("(?<=[。！？\n])")).filter(String::isNotEmpty)
        require(pieces.any(String::isNotBlank)) { "WRITING_FEEDBACK_INPUT_INVALID" }
        // 긴 문단도 원문 문자를 버리지 않고 인접 구간만 합쳐 schema의 ID 수를 제한한다.
        val groupSize = (pieces.size + 79) / 80
        return pieces.chunked(groupSize).mapIndexed { index, group -> Segment("S${index + 1}", group.joinToString("")) }
    }

    fun requirements(checklist: JsonArray): List<Pair<String, String>> {
        require(checklist.isNotEmpty() && checklist.size <= 30) { "WRITING_FEEDBACK_INPUT_INVALID" }
        return checklist.mapIndexed { index, value ->
            val text = value.jsonPrimitive.content
            require(text.isNotBlank()) { "WRITING_FEEDBACK_INPUT_INVALID" }
            "C${index + 1}" to text
        }
    }

    fun prompt(
        public: JsonObject, reference: JsonObject, answer: String,
        requirements: List<Pair<String, String>>, segments: List<Segment>,
    ): String = buildJsonObject {
        put("task", public)
        put("referenceAnswers", reference.getValue("referenceAnswers"))
        put("alternativeNote", reference.getValue("alternativeNote"))
        put("requirements", JsonArray(requirements.map { (id, text) ->
            buildJsonObject { put("id", id); put("text", text) }
        }))
        put("answer", answer)
        put("segments", JsonArray(segments.map { segment ->
            buildJsonObject { put("id", segment.id); put("text", segment.text) }
        }))
    }.toString()

    val instructions = """
        Give formative writing feedback only; do not assign a score, band, ability profile or weakness tag.
        The task is Korean-to-Japanese. Evaluate each listed requirement exactly once using its ID.
        Treat the learner's own valid choices as valid. A reference answer is an example, not the only correct answer.
        For MET evidence and corrections, select only supplied segment IDs. Never quote or invent an answer fragment.
        The server will restore the exact original text for each selected segment ID.
        Use Korean explanatory prose only in originText and Japanese explanatory prose only in learningText.
        Write a specific explanation of what the learner did or omitted for each requirement. Do not merely
        repeat a checklist question or copy the whole answer into an explanation. Keep answer evidence in segmentIds.
        Japanese learningText must contain no Korean words or particles, including short particles such as 을/를.
        Korean originText must be a Korean explanation, never a copied Japanese answer or checklist question.
        Example lanes: originText="반납 기한을 명확히 썼습니다."; learningText="返却期限を明確に示しています。"
        Necessary corrections require an actual error or an unmet stated requirement. Repair only the selected segment
        and preserve its other valid facts and communicative intent. A missing requirement can be explained without
        manufacturing text that the learner did not write. Never invent a reason, appointment, schedule or other
        learner circumstance to fill a missing requirement; ask the learner to provide their own true detail.
        For FREE tasks, a missing learner choice is UNMET and should be explained as a question or next step in
        observations. Do not fill it with the reference answer's people, places, dates, reasons or materials.
        Omit a necessaryCorrection when a concrete replacement would require choosing facts for the learner.
        Optional alternatives are not necessary corrections. Leave the array empty if no well-grounded suggestion
        improves a stated requirement. Do not assert that a valid original is inferior or that another expression is
        more natural or polite without a task-specific reason. Any replacement must be a full grammatical segment.
        Express genuine uncertainty in uncertainties, without converting a task-meaning error into success.
    """.trimIndent()

    fun schema(requirements: List<Pair<String, String>>, segments: List<Segment>): JsonObject {
        fun text(enum: List<String>? = null, description: String? = null) = buildJsonObject {
            put("type", "string")
            if (enum != null) put("enum", JsonArray(enum.map(::JsonPrimitive)))
            if (description != null) put("description", description)
        }
        fun obj(fields: Map<String, JsonElement>) = buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(fields))
            put("required", JsonArray(fields.keys.map(::JsonPrimitive)))
            put("additionalProperties", false)
        }
        fun list(item: JsonElement) = buildJsonObject { put("type", "array"); put("items", item) }
        val ids = requirements.map { it.first }
        val segmentIds = segments.map { it.id }
        val korean = "Explain the learner's evidence or omission in Korean Hangul. Never copy a Japanese answer or checklist question."
        val japanese = "Explain in Japanese only. No Korean words, Hangul particles, or copied full answer."
        val observation = obj(mapOf(
            "requirementId" to text(ids), "status" to text(listOf("MET", "UNMET", "UNCERTAIN")),
            "segmentIds" to list(text(segmentIds)), "originText" to text(description = korean),
            "learningText" to text(description = japanese),
        ))
        val correction = obj(mapOf(
            "requirementId" to text(ids), "segmentId" to text(segmentIds), "replacement" to text(),
            "originText" to text(description = korean), "learningText" to text(description = japanese),
        ))
        val optional = obj(mapOf(
            "segmentId" to text(segmentIds), "replacement" to text(),
            "originText" to text(description = korean), "learningText" to text(description = japanese),
        ))
        val uncertainty = obj(mapOf(
            "requirementId" to text(ids), "originText" to text(description = korean),
            "learningText" to text(description = japanese),
        ))
        return obj(mapOf(
            "observations" to list(observation), "necessaryCorrections" to list(correction),
            "optionalAlternatives" to list(optional), "uncertainties" to list(uncertainty),
        ))
    }

    fun validate(
        output: JsonElement, requirements: List<Pair<String, String>>, segments: List<Segment>,
    ): JsonObject {
        try {
            val root = output.jsonObject
            require(root.keys == setOf("observations", "necessaryCorrections", "optionalAlternatives", "uncertainties"))
            val known = segments.associateBy(Segment::id)
            val ids = requirements.map { it.first }.toSet()
            fun fields(value: JsonElement, expected: Set<String>): JsonObject {
                val obj = value.jsonObject
                require(obj.keys == expected)
                return obj
            }
            fun prose(obj: JsonObject) {
                val origin = obj.getValue("originText").jsonPrimitive.content
                val learning = obj.getValue("learningText").jsonPrimitive.content
                require(origin.isNotBlank() && learning.isNotBlank() && origin.length <= 4000 && learning.length <= 4000)
                require(Regex("[가-힣ㄱ-ㅎㅏ-ㅣ]").containsMatchIn(origin))
                require(!Regex("[\\u1100-\\u11ff\\u3130-\\u318f\\ua960-\\ua97f\\uac00-\\ud7ff\\uffa0-\\uffdc]").containsMatchIn(learning))
            }
            val observations = root.getValue("observations").jsonArray
            require(observations.size == ids.size)
            val statuses = mutableMapOf<String, String>()
            val observed = observations.map { raw ->
                val row = fields(raw, setOf("requirementId", "status", "segmentIds", "originText", "learningText"))
                val id = row.getValue("requirementId").jsonPrimitive.content
                val status = row.getValue("status").jsonPrimitive.content
                require(id in ids && id !in statuses && status in setOf("MET", "UNMET", "UNCERTAIN"))
                statuses[id] = status
                val spans = row.getValue("segmentIds").jsonArray.map { it.jsonPrimitive.content }
                require(spans.size <= known.size && spans.distinct().size == spans.size && spans.all { it in known })
                require(status != "MET" || spans.any { known.getValue(it).text.isNotBlank() })
                prose(row)
                JsonObject(row + ("answerQuotes" to JsonArray(spans.map { JsonPrimitive(known.getValue(it).text) })))
            }
            require(statuses.keys == ids)
            fun rewrite(array: JsonArray, optional: Boolean): JsonArray {
                require(array.size <= 20)
                return JsonArray(array.map { raw ->
                    val expected = setOf("segmentId", "replacement", "originText", "learningText") +
                        if (optional) emptySet() else setOf("requirementId")
                    val row = fields(raw, expected)
                    val segment = known[row.getValue("segmentId").jsonPrimitive.content]
                        ?: error("WRITING_FEEDBACK_SEGMENT_UNKNOWN")
                    if (!optional) require(row.getValue("requirementId").jsonPrimitive.content in ids)
                    val replacement = row.getValue("replacement").jsonPrimitive.content
                    require(replacement.isNotBlank() && replacement.length <= 4000 && replacement != segment.text)
                    require(!Regex("[\\u1100-\\u11ff\\u3130-\\u318f\\ua960-\\ua97f\\uac00-\\ud7ff\\uffa0-\\uffdc]").containsMatchIn(replacement))
                    prose(row)
                    JsonObject(row + ("original" to JsonPrimitive(segment.text)))
                })
            }
            val corrections = rewrite(root.getValue("necessaryCorrections").jsonArray, false)
            val alternatives = rewrite(root.getValue("optionalAlternatives").jsonArray, true)
            val uncertainties = root.getValue("uncertainties").jsonArray
            require(uncertainties.size <= ids.size)
            val checkedUncertainties = uncertainties.map { raw ->
                val row = fields(raw, setOf("requirementId", "originText", "learningText"))
                require(row.getValue("requirementId").jsonPrimitive.content in ids)
                prose(row)
                row
            }
            return buildJsonObject {
                put("policyVersion", version)
                put("resultPolicy", "REFERENCE_ONLY")
                put("observations", JsonArray(observed))
                put("necessaryCorrections", corrections)
                put("optionalAlternatives", alternatives)
                put("uncertainties", JsonArray(checkedUncertainties))
            }
        } catch (_: Exception) {
            throw IllegalArgumentException("WRITING_FEEDBACK_SCHEMA_INVALID")
        }
    }
}
