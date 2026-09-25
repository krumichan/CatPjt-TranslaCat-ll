package jp.co.translacat.languagelearning.features.growth.domain.model

import java.time.LocalDateTime

internal data class GrowthEvidence(
    val userId: Long,
    val source: String,
    val metricType: String?,
    val patternKey: String,
    val direction: String,
    val evidenceCount: Int,
    val weightedEvidence: Double,
    val averageConfidence: Double,
    val recommendedFocus: String?,
    val lastSeenAt: LocalDateTime,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)
