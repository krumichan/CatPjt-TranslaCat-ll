package jp.co.translacat.languagelearning.features.growth.domain.model

import java.time.LocalDate
import java.time.LocalDateTime

internal data class GrowthActivity(
    val id: Long = 0,
    val userId: Long,
    val source: String,
    val referenceId: String,
    val learningDate: LocalDate,
    val title: String,
    val durationSeconds: Long,
    val status: String,
    val overallScore: Double? = null,
    val evaluationConfidence: Double? = null,
    val startedAt: LocalDateTime,
    val completedAt: LocalDateTime?,
    val metadataJson: String = "{}",
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)
