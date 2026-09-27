package jp.co.translacat.languagelearning.features.listening.domain.model

import kotlinx.serialization.Serializable

@Serializable
internal data class ListeningMetricHistoryState(
    val id: Long, val userId: Long, val learningLanguage: String, val taskType: ListeningTaskType?,
    val metric: String, val rawScore: Double, val confidence: Double, val recencyWeight: Double,
    val assistanceWeight: Double, val evidenceWeight: Double, val finalWeight: Double,
    val assistanceLevel: String, val referenceActivityId: String, val referenceEvaluationId: String,
    val official: Boolean, val practice: Boolean, val profileApplied: Boolean,
    val evaluationVersion: String, val profilePolicyVersion: String, val createdAt: String,
)

@Serializable
internal data class ListeningRecommendationState(
    val id: Long, val userId: Long, val learningLanguage: String, val targetMetric: String,
    val recommendedActivity: String, val recommendedTask: String, val reason: String,
    val priority: Int, val expiresAt: String, val createdAt: String,
    val calculationVersion: String = "listening-recommendation", val ctaLabel: String = "학습 시작",
    val status: String = "ACTIVE", val explanationVersion: String? = null, val dismissedAt: String? = null,
)

@Serializable
internal data class ListeningMetricProfile(
    val metric: String, val score: Double?, val sampleCount: Int, val confidence: String,
    val weaknessState: String, val growthActive: Boolean, val recentDelta: Double?,
)
