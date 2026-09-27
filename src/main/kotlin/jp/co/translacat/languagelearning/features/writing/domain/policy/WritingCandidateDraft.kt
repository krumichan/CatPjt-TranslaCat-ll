package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.*

internal class WritingCandidateProtocolException(val code: String) : RuntimeException(code)

internal data class WritingCandidateMetadata(
    val scenarioCategory: String,
    val communicativeIntent: String,
    val taskArchetype: String,
    val grammarFocusCodes: List<String>,
    val lexicalFocusCodes: List<String>,
    val semanticSummary: String,
    val requiresBackgroundKnowledge: Boolean,
)

internal data class WritingCandidateDraft(
    val originText: String,
    val keywords: List<String>,
    val focusMetrics: List<String>,
    val focusReason: String,
    val providedFacts: List<String>,
    val requiredIntents: List<String>,
    val responseConstraints: List<String>,
    val metadata: WritingCandidateMetadata,
)

/** Python 후보 허용 필드와 검증 전 결정적 거부 조건을 적용한다. */
internal object WritingCandidatePolicy {
    private val owned = setOf(
        "order", "difficulty", "languageComplexityBand", "language_complexity_band",
        "writingType", "writing_type",
    )
    private val metrics = setOf("MEANING", "GRAMMAR", "VOCABULARY", "NATURALNESS", "EXPRESSION")
    private val scenarios = setOf(
        "DAILY_LIFE", "WORK", "TRAVEL", "SHOPPING", "FOOD", "SERVICE", "LEARNING",
        "HOBBY", "DIGITAL_LIFE", "SOCIAL", "SCHEDULE", "HEALTH_GENERAL",
    )
    private val intents = setOf(
        "DESCRIBE", "REQUEST", "CONFIRM", "REPORT", "SUGGEST", "DECLINE", "APOLOGIZE",
        "COMPARE", "EXPLAIN_REASON", "ASK_INFORMATION", "GIVE_INSTRUCTION", "EXPRESS_PREFERENCE", "SUMMARIZE",
    )
    private val candidateFields = setOf(
        "originText", "keywords", "focusMetrics", "focusReason", "providedFacts",
        "requiredIntents", "responseConstraints", "diversityMetadata",
    )
    private val metadataFields = setOf(
        "scenarioCategory", "communicativeIntent", "taskArchetype",
        "grammarFocusCodes", "lexicalFocusCodes", "semanticSummary", "requiresBackgroundKnowledge",
    )

    fun parse(raw: JsonObject): WritingCandidateDraft {
        val value = JsonObject(raw.filterKeys { it !in owned })
        exact(value.keys, candidateFields)
        val metadata = value.getValue("diversityMetadata") as? JsonObject ?: invalid()
        exact(metadata.keys, metadataFields)
        val scenario = token(metadata, "scenarioCategory", scenarios)
        val intent = token(metadata, "communicativeIntent", intents)
        val backgroundField = metadata["requiresBackgroundKnowledge"] as? JsonPrimitive ?: invalid()
        if (backgroundField.isString) invalid()
        val background = backgroundField.booleanOrNull ?: invalid()
        return WritingCandidateDraft(
            string(value, "originText", 2000), strings(value, "keywords", 20, 200),
            strings(value, "focusMetrics", 5, 200, min = 1).map(::canonical).also {
                if (it.any { metric -> metric !in metrics }) invalid()
            },
            string(value, "focusReason", 1000),
            strings(value, "providedFacts", 12, 2000),
            strings(value, "requiredIntents", 12, 2000),
            strings(value, "responseConstraints", 12, 2000),
            WritingCandidateMetadata(
                scenario, intent, string(metadata, "taskArchetype", 100),
                strings(metadata, "grammarFocusCodes", 20, 200),
                strings(metadata, "lexicalFocusCodes", 20, 200),
                string(metadata, "semanticSummary", 500), background,
            ),
        )
    }

    fun reason(request: JsonObject, type: WritingType, targetBand: Int, draft: WritingCandidateDraft): String? {
        val groups = listOf(draft.providedFacts, draft.requiredIntents, draft.responseConstraints)
        if (groups.flatten().any(String::isBlank)) return "GUIDANCE_CONTAINS_BLANK_VALUE"
        if (type == WritingType.GUIDED) {
            val names = listOf("PROVIDED_FACTS", "REQUIRED_INTENTS", "RESPONSE_CONSTRAINTS")
            for (index in groups.indices) if (groups[index].isEmpty()) return "GUIDED_${names[index]}_MISSING"
        } else if (groups.any(List<String>::isNotEmpty)) return "${type.name}_GUIDANCE_MUST_BE_EMPTY"
        if (draft.metadata.requiresBackgroundKnowledge) return "BACKGROUND_KNOWLEDGE_REQUIRED"
        if (draft.keywords.size != draft.keywords.toSet().size) return "DUPLICATE_KEYWORD"
        val selected = (request["selectedKeywords"] as? JsonArray ?: JsonArray(emptyList()))
            .map { it.jsonObject.getValue("key").jsonPrimitive.content }.toSet()
        if (!selected.containsAll(draft.keywords)) return "UNKNOWN_KEYWORD"
        if (draft.focusMetrics.size != draft.focusMetrics.toSet().size) return "DUPLICATE_FOCUS_METRIC"
        if (groups.any { it.size != it.toSet().size }) return "DUPLICATE_GUIDANCE"
        val sourceFields = linkedMapOf("originText" to draft.originText)
        if (type == WritingType.GUIDED) {
            sourceFields["providedFacts"] = draft.providedFacts.joinToString(" ")
            sourceFields["requiredIntents"] = draft.requiredIntents.joinToString(" ")
            sourceFields["responseConstraints"] = draft.responseConstraints.joinToString(" ")
        }
        if ((sourceFields.values + draft.focusReason).any(::controlCharacter)) return "CONTROL_CHARACTER"
        val spec =
            WritingDifficultyPolicy.spec(request.getValue("originLanguage").jsonPrimitive.content, type, targetBand)
        WritingDifficultyPolicy.reason(
            draft.originText, draft.focusReason, draft.providedFacts,
            draft.requiredIntents, draft.responseConstraints, spec,
        )?.let { return it }
        val originLanguage = request.getValue("originLanguage").jsonPrimitive.content
        val scriptCodes = mapOf(
            "originText" to "ORIGIN_TEXT_SCRIPT_MISMATCH",
            "providedFacts" to "GUIDED_PROVIDED_FACTS_SCRIPT_MISMATCH",
            "requiredIntents" to "GUIDED_REQUIRED_INTENTS_SCRIPT_MISMATCH",
            "responseConstraints" to "GUIDED_RESPONSE_CONSTRAINTS_SCRIPT_MISMATCH",
        )
        for ((field, text) in sourceFields) if (missingScript(originLanguage, text)) return scriptCodes.getValue(field)
        val plan = WritingDiversityPolicy.plan(request)
        if (draft.metadata.scenarioCategory !in plan.allowedScenarios) return "DIVERSITY_SCENARIO_LIMIT"
        if (draft.metadata.communicativeIntent !in plan.allowedIntents) return "DIVERSITY_INTENT_LIMIT"
        if (plan.eligibleKeywordKeys.isNotEmpty() && draft.keywords.isEmpty()) return "DIVERSITY_KEYWORD_MISSING"
        return null
    }

    fun noteNeedsLocalization(originLanguage: String, draft: WritingCandidateDraft): Boolean =
        missingScript(originLanguage, draft.focusReason)

    /** 원문·안내 필드만 묶어 노트와 분류만 바꾼 동일 문항을 식별한다. */
    fun taskKey(draft: WritingCandidateDraft): String {
        val content = buildJsonObject {
            put("originText", draft.originText)
            put("providedFacts", strings(draft.providedFacts))
            put("requiredIntents", strings(draft.requiredIntents))
            put("responseConstraints", strings(draft.responseConstraints))
        }
        return sha256(canonicalJson(content))
    }

    /** 승인 근거를 학습 문항·노트·분류·선택 키워드 범위에 결합한다. */
    fun contentHash(request: JsonObject, draft: WritingCandidateDraft): String {
        val selected = (request["selectedKeywords"] as? JsonArray ?: JsonArray(emptyList())).map { keyword ->
            val value = keyword.jsonObject
            buildJsonObject {
                put("key", value.getValue("key"))
                put("text", value.getValue("text"))
                put("type", value.getValue("type"))
            }
        }
        val material = buildJsonObject {
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
                },
            )
            put("focusReason", draft.focusReason)
            put("proposedClassification", metadata(draft.metadata))
            put("claimedKeywordKeys", strings(draft.keywords))
            put("selectedKeywords", JsonArray(selected))
        }
        return sha256(canonicalJson(material))
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun metadata(value: WritingCandidateMetadata): JsonObject = buildJsonObject {
        put("scenarioCategory", value.scenarioCategory)
        put("communicativeIntent", value.communicativeIntent)
        put("taskArchetype", value.taskArchetype)
        put("grammarFocusCodes", strings(value.grammarFocusCodes))
        put("lexicalFocusCodes", strings(value.lexicalFocusCodes))
        put("semanticSummary", value.semanticSummary)
        put("requiresBackgroundKnowledge", value.requiresBackgroundKnowledge)
    }

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

    internal fun canonicalJson(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") {
            JsonPrimitive(it.key).toString() + ":" + canonicalJson(it.value)
        }

        is JsonArray -> value.joinToString(",", "[", "]") { canonicalJson(it) }
        else -> value.toString()
    }

    private fun exact(actual: Set<String>, expected: Set<String>) {
        if (actual != expected) invalid()
    }

    private fun token(value: JsonObject, name: String, allowed: Set<String>): String =
        canonical(string(value, name, 100)).also { if (it !in allowed) invalid() }

    private fun canonical(value: String) = value.trim().uppercase(Locale.ROOT)
        .replace(Regex("[^A-Z0-9]+"), "_").trim('_')

    private fun string(value: JsonObject, name: String, max: Int): String {
        val text = value[name] as? JsonPrimitive ?: invalid()
        if (!text.isString) invalid()
        return text.content.trim().also { if (it.isEmpty() || it.length > max) invalid() }
    }

    private fun strings(value: JsonObject, name: String, maxCount: Int, maxLength: Int, min: Int = 0): List<String> {
        val array = value[name] as? JsonArray ?: invalid()
        if (array.size !in min..maxCount) invalid()
        return array.map { entry ->
            val text = entry as? JsonPrimitive ?: invalid()
            if (!text.isString) invalid()
            text.content.trim().also { if (it.isEmpty() || it.length > maxLength) invalid() }
        }
    }

    private fun missingScript(language: String, text: String): Boolean {
        val base = language.replace('_', '-').substringBefore('-').lowercase(Locale.ROOT)
        return when (base) {
            "ko" -> text.none { it.code in 0xAC00..0xD7A3 || it.code in 0x1100..0x11FF }
            "ja" -> text.none { it.code in 0x3040..0x30FF || it.code in 0x3400..0x4DBF || it.code in 0x4E00..0x9FFF }
            "en" -> text.none { it.code in 65..90 || it.code in 97..122 }
            else -> false
        }
    }

    private fun controlCharacter(text: String) = text.any {
        Character.getType(it) == Character.CONTROL.toInt() && it !in "\t\r\n"
    }

    private fun invalid(): Nothing = throw WritingCandidateProtocolException("CANDIDATE_SCHEMA")
}
