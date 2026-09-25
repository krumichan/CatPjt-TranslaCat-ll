package jp.co.translacat.languagelearning.features.growth.domain.model

internal data class GrowthMetric(
    val metricType: String,
    val state: String,
    val score: Double?,
    val confidence: Double?,
    val notEvaluableReason: String?,
)
