package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.text.Normalizer

internal data class WritingRepairObservation(val code: String, val evidenceIds: List<String>)

/** Existing one-shot B4→B5 editorial option; it never upgrades a band without a fresh review. */
internal data class WritingDifficultyRepairPlan(
    val original: WritingCandidateDraft,
    val originalHash: String,
    val order: Int,
    val writingType: WritingType,
    val missing: List<WritingRepairObservation>,
    val gaps: List<WritingRepairObservation>,
) {
    fun requireBinding(request: JsonObject) {
        require(
            order > 0 && request.getValue("writingType").jsonPrimitive.content == writingType.name &&
                originalHash == WritingCandidatePolicy.contentHash(request, original),
        ) { "DIFFICULTY_REPAIR_BINDING_INVALID" }
    }

    fun changeReason(request: JsonObject, revised: WritingCandidateDraft): String? {
        requireBinding(request)
        if (revised.keywords != original.keywords || revised.focusMetrics != original.focusMetrics)
            return "DIFFICULTY_REPAIR_SCOPE_CHANGED"
        val old = original.metadata
        val current = revised.metadata
        if (old.scenarioCategory != current.scenarioCategory || old.communicativeIntent != current.communicativeIntent ||
            old.taskArchetype != current.taskArchetype || old.lexicalFocusCodes != current.lexicalFocusCodes ||
            old.requiresBackgroundKnowledge != current.requiresBackgroundKnowledge
        )
            return "DIFFICULTY_REPAIR_SCOPE_CHANGED"
        fun normalized(value: WritingCandidateDraft): JsonObject = buildJsonObject {
            fun clean(text: String) = Regex("\\s+").replace(Normalizer.normalize(text, Normalizer.Form.NFC), " ").trim()
            put("originText", clean(value.originText))
            put("providedFacts", JsonArray(value.providedFacts.map { JsonPrimitive(clean(it)) }))
            put("requiredIntents", JsonArray(value.requiredIntents.map { JsonPrimitive(clean(it)) }))
            put("responseConstraints", JsonArray(value.responseConstraints.map { JsonPrimitive(clean(it)) }))
        }
        if (normalized(original) == normalized(revised)) return "DIFFICULTY_REPAIR_UNCHANGED"
        return null
    }

    fun revisionHash(request: JsonObject, revised: WritingCandidateDraft): String {
        requireBinding(request)
        fun observations(values: List<WritingRepairObservation>) = JsonArray(
            values.map { row ->
                JsonArray(listOf(JsonPrimitive(row.code), strings(row.evidenceIds)))
            },
        )

        val value = buildJsonObject {
            put("policy", "writing-difficulty-targeted-repair-v2")
            put("base", originalHash)
            put("revised", WritingCandidatePolicy.contentHash(request, revised))
            put("slot", order)
            put("missing", observations(missing))
            put("gaps", observations(gaps))
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(WritingCandidatePolicy.canonicalJson(value).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun reviewPayload(request: JsonObject, revised: WritingCandidateDraft): JsonObject = buildJsonObject {
        put("revisionHash", revisionHash(request, revised))
        put(
            "originalTask",
            buildJsonObject {
                put("id", "B0")
                put("learnerVisible", false)
                put("task", taskContent(original))
            },
        )
    }

    fun generationSchema(request: JsonObject): JsonObject {
        requireBinding(request)
        val band = 5
        var schema = WritingGenerationSchema.build(
            request, writingType, band,
            request.getValue("originLanguage").jsonPrimitive.content,
        )
        schema = schema.patch(listOf("properties", "items", "minItems"), JsonPrimitive(1))
        schema = schema.patch(listOf("properties", "items", "maxItems"), JsonPrimitive(1))
        val draftPath = listOf("\$defs", "WritingDraft", "properties")
        schema = schema.patch(draftPath + listOf("keywords", "minItems"), JsonPrimitive(original.keywords.size))
        schema = schema.patch(draftPath + listOf("keywords", "maxItems"), JsonPrimitive(original.keywords.size))
        if (original.keywords.isNotEmpty())
            schema = schema.patch(draftPath + listOf("keywords", "items", "enum"), strings(original.keywords))
        schema = schema.patch(draftPath + listOf("focusMetrics", "minItems"), JsonPrimitive(original.focusMetrics.size))
        schema = schema.patch(draftPath + listOf("focusMetrics", "maxItems"), JsonPrimitive(original.focusMetrics.size))
        schema = schema.patch(
            draftPath + listOf("focusMetrics", "items"),
            buildJsonObject {
                put("type", "string"); put("enum", strings(original.focusMetrics))
            },
        )
        schema = schema.patch(
            listOf("\$defs", "ScenarioCategory", "enum"), strings(listOf(original.metadata.scenarioCategory)),
        )
        schema = schema.patch(
            listOf("\$defs", "CommunicativeIntent", "enum"), strings(listOf(original.metadata.communicativeIntent)),
        )
        val metadataPath = listOf("\$defs", "DraftDiversityMetadata", "properties")
        schema = schema.patch(
            metadataPath + listOf("taskArchetype", "enum"),
            strings(listOf(original.metadata.taskArchetype)),
        )
        val lexical = original.metadata.lexicalFocusCodes
        schema = schema.patch(metadataPath + listOf("lexicalFocusCodes", "minItems"), JsonPrimitive(lexical.size))
        schema = schema.patch(metadataPath + listOf("lexicalFocusCodes", "maxItems"), JsonPrimitive(lexical.size))
        if (lexical.isNotEmpty())
            schema = schema.patch(metadataPath + listOf("lexicalFocusCodes", "items", "enum"), strings(lexical))
        return schema
    }

    fun generationPrompt(request: JsonObject): String {
        requireBinding(request)
        val originLanguage = request.getValue("originLanguage").jsonPrimitive.content
        val learningLanguage = request.getValue("learningLanguage").jsonPrimitive.content
        val operations = Json.parseToJsonElement(resource("repair-operations.json")).jsonObject
        val payload = buildJsonObject {
            put("originLanguage", originLanguage)
            put("learningLanguage", learningLanguage)
            put("writingType", writingType.name)
            put("selectedKeywords", request["selectedKeywords"] ?: JsonArray(emptyList()))
            put("difficultySpec", WritingGenerationPrompt.specPayload(originLanguage, writingType, 5))
            put("writingDiversityPlan", WritingDiversityPolicy.plan(request).payload())
            put(
                "sourceLanguageContract",
                WritingGenerationPrompt.sourceLanguageContract(originLanguage, learningLanguage),
            )
            put(
                "difficultyRepair",
                buildJsonObject {
                    put("policyVersion", "writing-difficulty-targeted-repair-v2")
                    put("baseContentHash", originalHash)
                    put("originalDraft", draftJson(original))
                    put(
                        "operations",
                        JsonArray(
                            buildList {
                                for (row in missing) add(operation(row, operations.getValue("missing").jsonObject))
                                for (row in gaps) add(operation(row, operations.getValue("gaps").jsonObject))
                            },
                        ),
                    )
                },
            )
        }
        return resource("repair-generation-prefix.txt").replace("\r\n", "\n") +
            "<learning-data>\n" + WritingCandidatePolicy.canonicalJson(payload)
            .replace("<", "\\u003c").replace(">", "\\u003e") + "\n</learning-data>"
    }

    private fun operation(row: WritingRepairObservation, definitions: JsonObject) = buildJsonObject {
        put("code", row.code)
        put("evidenceSegmentIds", strings(row.evidenceIds))
        put("instruction", definitions.getValue(row.code))
    }

    companion object {
        fun plan(
            request: JsonObject, original: WritingCandidateDraft, order: Int,
            writingType: WritingType, targetBand: Int, review: WritingReview,
        ): WritingDifficultyRepairPlan? {
            if (targetBand != 5 || review.difficultyStatus != "ASSESSED" || review.estimatedBand != 4 ||
                review.verdict != "PASS" || review.issues.isNotEmpty() || review.observedType != writingType.name ||
                review.checks.any { it.status != "PASS" }
            ) return null
            val checks = review.demands ?: return null
            if (checks.any { it.status == "UNSURE" }) return null
            val missing = checks.filter { it.status == "MISSING" }
                .map { WritingRepairObservation(it.code, it.evidenceIds) }
            val gaps = checks.mapNotNull { it.gapCode?.let { gap -> WritingRepairObservation(gap, it.evidenceIds) } }
            if (missing.isEmpty() && gaps.isEmpty()) return null
            return WritingDifficultyRepairPlan(
                original, WritingCandidatePolicy.contentHash(request, original),
                order, writingType, missing, gaps,
            )
        }

        fun draftJson(value: WritingCandidateDraft): JsonObject = buildJsonObject {
            put("originText", value.originText)
            put("keywords", strings(value.keywords))
            put("focusMetrics", strings(value.focusMetrics))
            put("focusReason", value.focusReason)
            put("providedFacts", strings(value.providedFacts))
            put("requiredIntents", strings(value.requiredIntents))
            put("responseConstraints", strings(value.responseConstraints))
            put("diversityMetadata", WritingCandidatePolicy.metadata(value.metadata))
        }

        fun taskContent(value: WritingCandidateDraft): JsonObject = buildJsonObject {
            put("originText", value.originText)
            put("providedFacts", strings(value.providedFacts))
            put("requiredIntents", strings(value.requiredIntents))
            put("responseConstraints", strings(value.responseConstraints))
        }

        private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
        private fun resource(name: String) = requireNotNull(javaClass.getResource("/writing/$name")).readText()
        private fun JsonObject.patch(path: List<String>, value: JsonElement): JsonObject {
            val head = path.first()
            val replacement = if (path.size == 1) value else
                getValue(head).jsonObject.patch(path.drop(1), value)
            return JsonObject(this + (head to replacement))
        }
    }
}
