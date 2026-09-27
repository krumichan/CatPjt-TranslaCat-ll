package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingAnswer
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingItem
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingSet
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.util.*

internal data class WritingEvaluationContext(
    val requestId: String,
    val compactRequestJson: String,
    val originLanguage: String,
    val learningLanguage: String,
    val learningDate: LocalDate,
    val canonicalKeys: List<String>,
)

/** BE WritingEvaluationRequestFactory의 snapshot/사용 문항 keyword 선택과 Pydantic field order. */
internal object WritingEvaluationContextBuilder {
    fun build(
        requestId: String,
        set: WritingSet,
        item: WritingItem,
        answer: WritingAnswer,
        originLanguage: String,
        learningLanguage: String,
        learningDate: LocalDate,
    ): WritingEvaluationContext {
        require(set.id == item.setId && item.id == answer.itemId && set.userId == answer.userId)
        val snapshot = Json.parseToJsonElement(set.snapshotJson).jsonObject
        val selected = snapshot["selectedKeywords"] as? JsonArray ?: JsonArray(emptyList())
        val used =
            Json.parseToJsonElement(item.keywordsJson).jsonArray.map { it.jsonPrimitive.content.lowercase(Locale.ROOT) }
                .toSet()
        val filtered = if (used.isEmpty()) selected else JsonArray(
            selected.filter { keyword ->
                val value = keyword.jsonObject
                listOf("text", "key", "canonicalKey").any { field ->
                    (value[field] as? JsonPrimitive)?.contentOrNull?.lowercase(Locale.ROOT) in used
                }
            },
        )
        val relevant = if (filtered.isEmpty()) selected else filtered
        val canonical = relevant.map { value ->
            val keyword = value.jsonObject
            (keyword["canonicalKey"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: keyword.getValue("text").jsonPrimitive.content.lowercase(Locale.ROOT)
        }
        val request = buildJsonObject {
            put("requestId", requestId)
            put("context", "DAILY")
            put("writingType", set.writingType.name)
            put("originLanguage", originLanguage)
            put("learningLanguage", learningLanguage)
            put("originSentence", item.originText)
            put("userAnswer", answer.text)
            put("difficulty", item.difficulty.name)
            put("keywords", relevant)
            put("focusMetrics", Json.parseToJsonElement(item.focusMetricsJson))
            put("learningProfileSummary", snapshot["learningProfile"] ?: JsonNull)
            put("taskType", JsonNull)
            put("translationSourceText", JsonNull)
            put("providedFacts", list(item.providedFactsJson))
            put("requiredIntents", list(item.requiredIntentsJson))
            put("responseConstraints", list(item.responseConstraintsJson))
        }
        return WritingEvaluationContext(
            requestId, request.toString(), originLanguage, learningLanguage, learningDate, canonical,
        )
    }

    private fun list(json: String?): JsonArray =
        json?.let { Json.parseToJsonElement(it).jsonArray } ?: JsonArray(emptyList())
}
