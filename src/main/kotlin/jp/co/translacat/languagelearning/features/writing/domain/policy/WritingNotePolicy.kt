package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*

/** Current Python note_localization/NoteReview contracts; the proposal is never approval. */
internal object WritingNotePolicy {
    val localizationInstructions: String by lazy { resource("note-localization-system-prompt.txt").trimEnd() }
    val reviewInstructions: String by lazy { resource("note-review-system-prompt.txt").trimEnd() }
    private val reviewPrefix: String by lazy { resource("note-review-prefix.txt") }
    val localizationSchema: JsonObject by lazy {
        Json.parseToJsonElement(resource("note-localization-schema.json")).jsonObject
    }
    private val reviewBaseSchema: JsonObject by lazy {
        Json.parseToJsonElement(resource("note-review-schema.json")).jsonObject
    }

    fun localizationPrompt(
        request: JsonObject, draft: WritingCandidateDraft, candidateId: String, contentHash: String,
    ): String {
        val value = buildJsonObject {
            put("candidateId", candidateId)
            put("contentHash", contentHash)
            put("originLanguage", request.getValue("originLanguage"))
            put("learningLanguage", request.getValue("learningLanguage"))
            put("writingType", request.getValue("writingType"))
            put(
                "content",
                buildJsonObject {
                    put("originText", draft.originText)
                    put("providedFacts", strings(draft.providedFacts))
                    put("requiredIntents", strings(draft.requiredIntents))
                    put("responseConstraints", strings(draft.responseConstraints))
                    put("focusReason", draft.focusReason)
                },
            )
        }
        return "<writing-note-data>\n" + value.toString().replace("<", "\\u003c").replace(">", "\\u003e") +
            "\n</writing-note-data>"
    }

    fun reviewPrompt(
        request: JsonObject, draft: WritingCandidateDraft, candidateId: String, contentHash: String,
    ): String {
        val segments = buildList {
            add(segment("O1", "originText", draft.originText))
            for ((prefix, field, values) in listOf(
                Triple("F", "providedFacts", draft.providedFacts),
                Triple("I", "requiredIntents", draft.requiredIntents),
                Triple("C", "responseConstraints", draft.responseConstraints),
            )) values.forEachIndexed { index, text -> add(segment("$prefix${index + 1}", field, text)) }
            add(segment("N1", "focusReason", draft.focusReason))
        }
        val data = buildJsonObject {
            put("candidateId", candidateId)
            put("contentHash", contentHash)
            put("originLanguage", request.getValue("originLanguage"))
            put("learningLanguage", request.getValue("learningLanguage"))
            put("writingType", request.getValue("writingType"))
            put("segments", JsonArray(segments))
        }
        return reviewPrefix + "<writing-review-data>\n" + data.toString()
            .replace("<", "\\u003c").replace(">", "\\u003e") + "\n</writing-review-data>"
    }

    fun reviewSchema(draft: WritingCandidateDraft): JsonObject {
        val evidenceIds = buildList {
            add("O1")
            draft.providedFacts.indices.forEach { add("F${it + 1}") }
            draft.requiredIntents.indices.forEach { add("I${it + 1}") }
            draft.responseConstraints.indices.forEach { add("C${it + 1}") }
            add("N1")
        }
        val properties = reviewBaseSchema.getValue("properties").jsonObject
        val evidence = properties.getValue("evidenceSegmentIds").jsonObject
        val items = evidence.getValue("items").jsonObject
        return JsonObject(
            reviewBaseSchema + ("properties" to JsonObject(
                properties +
                    ("evidenceSegmentIds" to JsonObject(
                        evidence +
                            ("items" to JsonObject(items + ("enum" to strings(evidenceIds)))),
                    )),
            )),
        )
    }

    fun proposal(raw: JsonElement, candidateId: String, contentHash: String): String {
        val value = raw as? JsonObject ?: invalid("NOTE_PROPOSAL_SCHEMA_INVALID")
        if (value.keys != setOf("candidateId", "contentHash", "focusReason")) invalid("NOTE_PROPOSAL_SCHEMA_INVALID")
        if (text(value, "candidateId", 100) != candidateId) invalid("VERIFIER_IDENTITY_MISMATCH")
        if (text(value, "contentHash", 64) != contentHash) invalid("VERIFIER_CONTENT_HASH_MISMATCH")
        return text(value, "focusReason", 1000)
    }

    fun review(raw: JsonElement, candidateId: String, contentHash: String, draft: WritingCandidateDraft): String {
        val value = raw as? JsonObject ?: invalid("NOTE_REVIEW_SCHEMA_INVALID")
        if (value.keys != setOf(
                "candidateId", "contentHash", "confidence", "verdict", "issues",
                "evidenceSegmentIds",
            )
        ) invalid("NOTE_REVIEW_SCHEMA_INVALID")
        if (text(value, "candidateId", 100) != candidateId) invalid("VERIFIER_IDENTITY_MISMATCH")
        if (text(value, "contentHash", 64) != contentHash) invalid("VERIFIER_CONTENT_HASH_MISMATCH")
        val confidence = value["confidence"]
        if (confidence != JsonNull) {
            val number = (confidence as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                ?: invalid("NOTE_REVIEW_CONFIDENCE_INVALID")
            if (!number.isFinite() || number !in 0.0..1.0) invalid("NOTE_REVIEW_CONFIDENCE_INVALID")
        }
        val verdict = text(value, "verdict", 10)
        if (verdict !in setOf("PASS", "REJECT", "UNSURE")) invalid("NOTE_REVIEW_SCHEMA_INVALID")
        val issues = (value["issues"] as? JsonArray)?.map { it.jsonPrimitive.content }
            ?: invalid("NOTE_REVIEW_SCHEMA_INVALID")
        if (issues.size > 4 || issues.size != issues.toSet().size || issues.any {
                it !in setOf("ORIGIN_LANGUAGE", "ANSWER_LEAK", "UNSUPPORTED_FOCUS_REASON", "INTERNAL_CLAIM")
            }) invalid("NOTE_REVIEW_SCHEMA_INVALID")
        val evidence = (value["evidenceSegmentIds"] as? JsonArray)?.map { it.jsonPrimitive.content }
            ?: invalid("NOTE_REVIEW_SCHEMA_INVALID")
        val allowed = reviewSchema(draft).getValue("properties").jsonObject.getValue("evidenceSegmentIds")
            .jsonObject.getValue("items").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet()
        if (evidence.size > 12 || evidence.size != evidence.toSet().size || evidence.any { it !in allowed })
            invalid("VERIFIER_EVIDENCE_SEGMENT_INVALID")
        if (verdict != "UNSURE" && "N1" !in evidence) invalid("NOTE_REVIEW_EVIDENCE_INVALID")
        if ((verdict == "REJECT") != issues.isNotEmpty()) invalid("NOTE_REVIEW_VERDICT_MISMATCH")
        return when (verdict) {
            "PASS" -> "VERIFIED_NOTE_LOCALIZED"
            "REJECT" -> "NOTE_${issues.first()}"
            else -> "NOTE_VERIFIER_UNCERTAIN"
        }
    }

    private fun text(value: JsonObject, name: String, max: Int): String {
        val raw = value[name] as? JsonPrimitive ?: invalid("NOTE_SCHEMA_FIELD_INVALID")
        if (!raw.isString || raw.content.length !in 1..max) invalid("NOTE_SCHEMA_FIELD_INVALID")
        return raw.content
    }

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
    private fun segment(id: String, field: String, content: String) = buildJsonObject {
        put("id", id); put("field", field); put("text", content)
    }

    private fun resource(name: String) = requireNotNull(javaClass.getResource("/writing/$name"))
        .readText().replace("\r\n", "\n")

    private fun invalid(code: String): Nothing = throw WritingReviewProtocolException(code)
}
