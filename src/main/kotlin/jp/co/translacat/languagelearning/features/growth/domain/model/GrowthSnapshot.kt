package jp.co.translacat.languagelearning.features.growth.domain.model

internal data class GrowthSnapshot(
    val userId: Long, val sourceInstanceId: String, val sequence: Long, val preview: Boolean,
    val profile: GrowthProfile?, val masteries: List<KeywordMastery>, val signals: Map<String, List<GrowthSignal>>,
)
internal data class GrowthActivityPage(
    val userId: Long, val sourceInstanceId: String, val sequence: Long,
    val activities: List<Pair<GrowthActivity, List<GrowthMetric>>>, val nextAfterId: Long?, val projectionRevision: String,
)
