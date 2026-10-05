package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 원본 평가 계약을 보존하며 피드백 필드의 기존 언어·교정 역할을 명시한다. */
internal object WritingEvaluationAssets {
    const val promptVersion = "writing-evaluation"
    val instructions: String by lazy {
        requireNotNull(javaClass.getResource("/writing/evaluation-system-prompt.txt"))
            .readText().replace("\r\n", "\n").trimEnd('\r', '\n') + "\n\n" + feedbackFieldClarifications
    }
    val schema: JsonObject by lazy {
        // 공유 리소스의 타입·필수 필드는 보존하고 불변 복사에만 피드백 필드 설명을 추가한다.
        withFeedbackFieldDescriptions(Json.parseToJsonElement(
            requireNotNull(javaClass.getResource("/writing/evaluation-schema.json")).readText(),
        )).jsonObject
    }

    private val feedbackFieldClarifications = """
        # Feedback field language
        - Every originText feedback field, including nested correction explanations, must use the request originLanguage for its explanatory prose.
        - Every learningText feedback field, including nested correction explanations, must use the request learningLanguage for its explanatory prose. Brief exact task/answer quotations may retain their original language; the surrounding explanation must not switch languages or copy the originLanguage explanation.

        # Evidence-based corrections and optional refinements
        - Weaknesses and corrections require an evidenced error or an unmet explicit task constraint. A different acceptable phrasing alone is not an error.
        - Optional style, register, or politeness refinements belong in recommendedAnswers, not weaknesses or corrections, unless the task explicitly requires the unmet register or formality.
        - A correction must preserve already-valid facts, actions, and communicative intent. If an actual task-meaning mismatch needs correction, identify that mismatch explicitly instead of silently replacing an already-correct meaning. Respect the task's stated scope and concision constraints.

        # Profile signal field roles
        - Put demonstrated positive observations from the learner's actual answer in strengthTags. grammarPatterns, vocabularyPatterns, naturalnessPatterns, expressionPatterns, and meaningPatterns feed existing weakness/error-pattern fields: include only evidenced problems in the corresponding metric, never positive observations. Return an empty array when no such problem was observed.
        - recommendedFocus contains practice suggestions, not evidence of expressions already used or mastered. Suggested words, corrected fragments, and recommendedAnswers are not the learner's usage or achievement evidence unless independently present in the actual submitted answer; do not promote a suggested improvement into an observed strength or weakness.
    """.trimIndent()

    private val correctionFragmentDescriptions = mapOf(
        "original" to "A non-empty fragment quoted from the learner's actual submitted answer. " +
            "Return the fragment itself as a string, not a translation, explanation, or serialized bilingual feedback object.",
        "corrected" to "The non-empty replacement answer fragment in the request learningLanguage, corresponding to original. " +
            "Return the fragment itself as a string. Do not serialize an object containing originText, learningText, " +
            "category, explanation, or other feedback fields into this string. Bilingual correction reasons belong in the sibling explanation object.",
    )
    private val profilePatternMetrics = mapOf(
        "grammarPatterns" to "GRAMMAR", "vocabularyPatterns" to "VOCABULARY",
        "naturalnessPatterns" to "NATURALNESS", "expressionPatterns" to "EXPRESSION",
        "meaningPatterns" to "MEANING",
    )

    private fun withFeedbackFieldDescriptions(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, child) ->
            val described = withFeedbackFieldDescriptions(child)
            if (key in setOf("originText", "learningText") && described is JsonObject) {
                val language = if (key == "originText") "originLanguage" else "learningLanguage"
                JsonObject(described + ("description" to JsonPrimitive(
                    "Write this feedback's explanatory prose in the request $language. " +
                        "Brief exact task or answer quotations may keep their original language; " +
                        "the surrounding explanation must use $language, including nested correction reasons.",
                )))
            } else if (key in correctionFragmentDescriptions && described is JsonObject) {
                // 원문·수정문은 답안 fragment이며 bilingual 이유 객체와 다른 출력 역할을 가진다.
                JsonObject(described + ("description" to JsonPrimitive(correctionFragmentDescriptions.getValue(key))))
            } else if (key in profilePatternMetrics && described is JsonObject) {
                // 기존 약점 저장 대상인 다섯 배열에만 producer 의미를 결합한다. 값은 재분류하지 않는다.
                val metric = profilePatternMetrics.getValue(key)
                val items = described.getValue("items").jsonObject
                JsonObject(described + mapOf(
                    "description" to JsonPrimitive(
                        "Observed $metric deficiencies only. This array feeds an existing weakness/error-pattern field. " +
                            "Return [] when no actual $metric problem is evidenced in the learner's answer. " +
                            "Put positive observations in strengthTags, not in this array.",
                    ),
                    "items" to JsonObject(items + ("description" to JsonPrimitive(
                        "One specific $metric error or unmet task requirement evidenced in the actual submitted answer. " +
                            "Do not describe correct or neutral usage, optional refinements, or suggested expressions as a deficiency; " +
                            "positive observations belong in strengthTags. If none exists, leave the parent array empty.",
                    ))),
                ))
            } else described
        })
        is JsonArray -> JsonArray(value.map(::withFeedbackFieldDescriptions))
        else -> value
    }

    fun prompt(context: String, originLanguage: String, learningLanguage: String, compactRequestJson: String): String {
        require(context in setOf("DAILY", "LEVEL_TEST"))
        require(originLanguage.isNotBlank() && learningLanguage.isNotBlank())
        Json.parseToJsonElement(compactRequestJson).jsonObject
        return """
            # Current task
            Evaluate this $context writing answer.
            Feedback originText fields MUST be written in $originLanguage.
            Feedback learningText fields and recommendedAnswers MUST be written in $learningLanguage.

            <evaluation-data>
            $compactRequestJson
            </evaluation-data>
        """.trimIndent()
    }
}
