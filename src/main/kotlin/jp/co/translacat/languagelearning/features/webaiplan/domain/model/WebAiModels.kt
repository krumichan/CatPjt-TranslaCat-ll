package jp.co.translacat.languagelearning.features.webaiplan.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
internal data class WebAiKeyword(val key: String, val text: String, val canonicalKey: String? = null)

@Serializable
internal data class WebAiArea(val code: String, val easy: Long, val standard: Long, val hard: Long) {
    fun count(difficulty: String): Long = when (difficulty) {
        "EASY" -> easy
        "STANDARD" -> standard
        "HARD" -> hard
        else -> 0
    }
}

@Serializable
internal data class WebAiPlanChange(
    val id: String? = null,
    val revision: Long? = null,
    val name: String,
    val timezone: String,
    val time: String,
    val recurrence: String,
    val weekdays: List<Int> = emptyList(),
    val monthDays: List<Int> = emptyList(),
    val includeLastDay: Boolean = false,
    val areas: List<WebAiArea>,
    val keywordMode: String = "DEFAULT",
    val keywords: List<WebAiKeyword> = emptyList(),
    val focus: String = "",
    val enabled: Boolean = true,
)

@Serializable
internal data class WebAiPlan(
    val id: String,
    val revision: Long,
    val name: String,
    val originLanguage: String,
    val learningLanguage: String,
    val feedbackLanguage: String,
    val timezone: String,
    val time: String,
    val recurrence: String,
    val weekdays: List<Int>,
    val monthDays: List<Int>,
    val includeLastDay: Boolean,
    val areas: List<WebAiArea>,
    val keywordMode: String,
    val keywords: List<WebAiKeyword>,
    val focus: String,
    val enabled: Boolean,
    val updatedAt: String,
)

@Serializable
internal data class WebAiRun(
    val id: String,
    val planId: String,
    val executionKey: String,
    val originLanguage: String,
    val learningLanguage: String,
    val feedbackLanguage: String,
    val areas: List<WebAiArea>,
    val keywords: List<WebAiKeyword>,
    val prompt: String,
    val createdAt: String,
    val learningDate: String,
    val planName: String,
    val planRevision: Long,
    val timezone: String,
)

@Serializable
internal data class WebAiSubmittedItem(
    val itemId: String,
    val areaCode: String,
    val difficulty: String,
    val promptText: String,
    val answerText: String,
    val passageText: String? = null,
    val sourceText: String? = null,
    val requiredIntents: List<String> = emptyList(),
    val externalScore: Double? = null,
    val externalFeedbackMarkdown: String = "",
    val keywordKeys: List<String> = emptyList(),
)

@Serializable
internal data class WebAiMetric(val code: String, val value: Double)

@Serializable
internal data class WebAiVerifiedItem(
    val item: WebAiSubmittedItem,
    val status: String,
    val officialScore: Double? = null,
    val feedback: JsonObject = buildJsonObject {},
    val metrics: List<WebAiMetric> = emptyList(),
    val writingScores: List<Double> = emptyList(),
    val reason: String? = null,
)

@Serializable
internal data class WebAiResultSubmission(
    val runId: String,
    val submissionId: String,
    val originalFeedbackMarkdown: String,
    val items: List<WebAiSubmittedItem>,
)

@Serializable
internal data class WebAiResult(
    val id: String,
    val runId: String,
    val userId: Long,
    val status: String,
    val originalFeedbackMarkdown: String,
    val items: List<WebAiVerifiedItem>,
    val createdAt: String,
    val updatedAt: String,
    val originLanguage: String,
    val learningLanguage: String,
    val feedbackLanguage: String,
    val learningDate: String,
    val planName: String,
)

internal class WebAiException(val code: String, override val message: String, val status: Int = 400) :
    RuntimeException(message)
