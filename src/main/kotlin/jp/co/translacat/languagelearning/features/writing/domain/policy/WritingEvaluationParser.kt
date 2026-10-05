package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*

internal class WritingEvaluationProtocolException(val code: String, val validationPath: String = "output") :
    RuntimeException(code)
internal data class WritingEvaluationResult(val scores: WritingScores, val payload: JsonObject)

/** Python AiWritingEvaluationPayload의 필수 필드와 범위를 업무 경계에서 검사한다. */
internal object WritingEvaluationParser {
    private val scoreKeys = setOf("meaning", "grammar", "vocabulary", "naturalness", "expression")
    private val rootKeys =
        setOf("scores", "strengths", "weaknesses", "corrections", "recommendedAnswers", "explanation", "profileSignals")
    private val signalKeys = setOf(
        "strengthTags", "weaknessTags", "grammarPatterns", "vocabularyPatterns",
        "naturalnessPatterns", "expressionPatterns", "meaningPatterns", "recommendedFocus",
    )

    fun parse(output: JsonElement): WritingEvaluationResult {
        // 원문 대신 검사 중인 고정 필드 경로만 보존해 Provider 계약 실패를 진단한다.
        var path = "output"
        try {
            val root = output as? JsonObject ?: invalid()
            require(root.keys.all { it in rootKeys })
            path = "scores"
            val values = requiredObject(root, "scores", scoreKeys)
            require(values.keys == scoreKeys)
            val raw = WritingRawScores(
                number(values, "meaning"), number(values, "grammar"), number(values, "vocabulary"),
                number(values, "naturalness"), number(values, "expression"),
            )
            path = "strengths"
            array(root, "strengths", 20).forEachIndexed { index, value ->
                path = "strengths[$index]"
                bilingual(value, 4000)
            }
            path = "weaknesses"
            array(root, "weaknesses", 20).forEachIndexed { index, value ->
                path = "weaknesses[$index]"
                bilingual(value, 4000)
            }
            path = "corrections"
            array(root, "corrections", 30).forEachIndexed { index, element ->
                path = "corrections[$index]"
                val correction = element as? JsonObject ?: invalid()
                require(correction.keys == setOf("original", "corrected", "category", "explanation"))
                path = "corrections[$index].original"
                string(correction, "original", 1000)
                path = "corrections[$index].corrected"
                string(correction, "corrected", 1000)
                path = "corrections[$index].category"
                string(correction, "category", 100)
                path = "corrections[$index].explanation"
                bilingual(correction.getValue("explanation"), 4000)
            }
            path = "recommendedAnswers"
            val recommended = array(root, "recommendedAnswers", 3, required = true)
            require(recommended.size >= 2)
            recommended.forEach { require(it is JsonPrimitive && it.isString) }
            path = "explanation"
            bilingual(root["explanation"] ?: invalid(), 4000)
            path = "profileSignals"
            val signals = requiredObject(root, "profileSignals", signalKeys)
            signalKeys.forEach { key ->
                path = "profileSignals.$key"
                array(signals, key, 30).forEach {
                    require(it is JsonPrimitive && it.isString)
                }
            }
            return WritingEvaluationResult(WritingScoring.score(raw), root)
        } catch (_: Exception) {
            throw WritingEvaluationProtocolException("WRITING_EVALUATION_SCHEMA_INVALID", path)
        }
    }

    private fun requiredObject(parent: JsonObject, key: String, allowed: Set<String>): JsonObject {
        val value = parent[key] as? JsonObject ?: invalid()
        require(value.keys.all { it in allowed })
        return value
    }

    private fun number(parent: JsonObject, key: String): Double {
        val value = parent[key] as? JsonPrimitive ?: invalid()
        require(!value.isString)
        return value.doubleOrNull ?: invalid()
    }

    private fun string(parent: JsonObject, key: String, maximum: Int): String {
        val value = parent[key] as? JsonPrimitive ?: invalid()
        require(value.isString)
        return value.content.trim().also { require(it.isNotEmpty() && it.length <= maximum) }
    }

    private fun bilingual(value: JsonElement, maximum: Int) {
        val obj = value as? JsonObject ?: invalid()
        require(obj.keys == setOf("originText", "learningText"))
        string(obj, "originText", maximum)
        string(obj, "learningText", maximum)
    }

    private fun array(parent: JsonObject, key: String, maximum: Int, required: Boolean = false): JsonArray {
        if (!required && key !in parent) return JsonArray(emptyList())
        val values = parent[key] as? JsonArray ?: invalid()
        require(values.size <= maximum)
        return values
    }

    private fun invalid(): Nothing = throw WritingEvaluationProtocolException("WRITING_EVALUATION_SCHEMA_INVALID")
}
