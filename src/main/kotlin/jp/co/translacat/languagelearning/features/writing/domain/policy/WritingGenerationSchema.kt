package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import java.util.*

/** Python WritingCandidateBatch 기본 Schema에 request-local 기존 제약만 적용한다. */
internal object WritingGenerationSchema {
    private val base: JsonObject by lazy {
        Json.parseToJsonElement(
            requireNotNull(javaClass.getResource("/writing/candidate-base-schema.json")).readText(),
        ).jsonObject
    }

    fun build(request: JsonObject, type: WritingType, band: Int, originLanguage: String): JsonObject {
        val spec = WritingDifficultyPolicy.spec(originLanguage, type, band)
        val plan = WritingDiversityPolicy.plan(request)
        val definitions = base.getValue("\$defs").jsonObject.toMutableMap()
        definitions["ScenarioCategory"] = patch(
            definitions.getValue("ScenarioCategory").jsonObject,
            "enum" to strings(plan.allowedScenarios),
        )
        definitions["CommunicativeIntent"] = patch(
            definitions.getValue("CommunicativeIntent").jsonObject,
            "enum" to strings(plan.allowedIntents),
        )
        val draft = definitions.getValue("WritingDraft").jsonObject.toMutableMap()
        val fields = draft.getValue("properties").jsonObject.toMutableMap()
        val origin = fields.getValue("originText").jsonObject
        val sourcePattern = when (originLanguage.replace('_', '-').substringBefore('-').lowercase(Locale.ROOT)) {
            "ko" -> "[\\uac00-\\ud7a3\\u1100-\\u11ff]"
            "ja" -> "[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff]"
            "en" -> "[A-Za-z]"
            else -> null
        }
        fields["originText"] = patch(
            origin, "maxLength" to JsonPrimitive(spec.originMaxCharacters),
            *(if (type == WritingType.TRANSLATION && sourcePattern != null)
                arrayOf("pattern" to JsonPrimitive(sourcePattern)) else emptyArray()),
        )
        fields["focusReason"] = patch(
            fields.getValue("focusReason").jsonObject,
            "maxLength" to JsonPrimitive(spec.noteMaxCharacters),
        )
        val keywords = fields.getValue("keywords").jsonObject.toMutableMap()
        if (plan.eligibleKeywordKeys.isEmpty()) keywords["maxItems"] = JsonPrimitive(0)
        else {
            keywords["minItems"] = JsonPrimitive(1)
            keywords["items"] = patch(
                keywords.getValue("items").jsonObject,
                "enum" to strings(plan.eligibleKeywordKeys),
            )
        }
        fields["keywords"] = JsonObject(keywords)
        for (name in listOf("providedFacts", "requiredIntents", "responseConstraints")) {
            val guidance = fields.getValue(name).jsonObject.toMutableMap()
            // 안내는 원어로, 답안은 학습 언어로 작성한다는 기존 검증 계약을 생성 경계에도 명시한다.
            if (type == WritingType.GUIDED) {
                val learningLanguage = request.getValue("learningLanguage").jsonPrimitive.content
                guidance["description"] = JsonPrimitive(
                    "$name is learner-visible guidance in originLanguage ($originLanguage). " +
                        "Write the guidance in that language. Do not provide a model answer in " +
                        "learningLanguage ($learningLanguage).",
                )
            }

            guidance["minItems"] = JsonPrimitive(spec.guidanceMinEntries)
            guidance["maxItems"] = JsonPrimitive(spec.guidanceMaxEntries)
            guidance["items"] = patch(
                guidance.getValue("items").jsonObject,
                "maxLength" to JsonPrimitive(spec.guidanceMaxCharactersPerEntry),
            )
            fields[name] = JsonObject(guidance)
        }
        draft["properties"] = JsonObject(fields)
        definitions["WritingDraft"] = JsonObject(draft)
        return patch(base, "\$defs" to JsonObject(definitions))
    }

    private fun patch(base: JsonObject, vararg values: Pair<String, JsonElement>) = JsonObject(base + values)
    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
}
