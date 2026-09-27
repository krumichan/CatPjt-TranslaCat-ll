package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 현재 Python prompt registry와 서비스 Schema의 정적 이행본. */
internal object WritingEvaluationAssets {
    const val promptVersion = "writing-evaluation"
    val instructions: String by lazy {
        requireNotNull(javaClass.getResource("/writing/evaluation-system-prompt.txt"))
            .readText().replace("\r\n", "\n").trimEnd('\r', '\n')
    }
    val schema: JsonObject by lazy {
        Json.parseToJsonElement(
            requireNotNull(javaClass.getResource("/writing/evaluation-schema.json")).readText(),
        ).jsonObject
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
