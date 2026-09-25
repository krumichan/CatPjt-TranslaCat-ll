package jp.co.translacat.languagelearning.features.leveltest.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class LevelEvaluationData(
    val requestId: String? = null,
    val sessionId: Long,
    val itemId: Long,
    val domain: LevelTestDomain,
    val itemType: LevelTestItemType,
    val evaluable: Boolean,
    val score: Int? = null,
    val confidence: Double? = null,
    val transcript: String? = null,
    val metrics: List<JsonObject> = emptyList(),
    val strengths: List<String> = emptyList(),
    val improvements: List<String> = emptyList(),
    val recommendedAnswers: List<String> = emptyList(),
    val detailedFeedback: List<JsonObject> = emptyList(),
    val assessmentSignals: List<JsonObject> = emptyList(),
    val reasonCode: String? = null,
    val evaluationVersion: String,
    val promptVersion: String? = null,
    val usage: JsonObject? = null,
)
