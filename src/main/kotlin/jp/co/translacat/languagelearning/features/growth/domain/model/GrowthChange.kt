package jp.co.translacat.languagelearning.features.growth.domain.model

import java.time.LocalDate

/** 저장할 값이 아니라 LL이 수행할 업무 명령이다. 모델 응답 원문·답안은 포함하지 않는다. */
internal sealed interface GrowthChange {
    data class WritingScored(
        val learningDate: LocalDate,
        val difficulty: String,
        val scores: List<Double>,
        val signals: Map<String, List<String>>,
        val canonicalKeys: List<String>,
    ) : GrowthChange

    data class LearningPrepared(val learningDate: LocalDate) : GrowthChange

    data class SignalsTouched(val type: String, val values: List<String>) : GrowthChange
    data class KeywordsSelected(val learningDate: LocalDate, val canonicalKeys: List<String>) : GrowthChange
    data class ActivityRecorded(val activity: GrowthActivity, val metrics: List<GrowthMetric>?) : GrowthChange
    data class SpeakingScored(
        val activity: GrowthActivity,
        val metrics: List<GrowthMetric>,
        val formal: Boolean,
        val activityWeight: Double,
        val evidence: List<EvidenceFact>,
    ) : GrowthChange
}

internal data class EvidenceFact(
    val metricType: String?,
    val patternKey: String,
    val direction: String?,
    val confidence: Double,
    val recommendedFocus: String?,
)
