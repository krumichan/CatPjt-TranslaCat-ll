package jp.co.translacat.languagelearning.features.growth.application

import jp.co.translacat.languagelearning.features.growth.domain.model.*
import jp.co.translacat.languagelearning.features.growth.domain.repository.GrowthRepository
import java.time.LocalDate

/** Core 미커밋 명령의 transaction-local 조회용이다. save는 메모리 사본에만 적용한다. */
internal class GrowthPreview(private val base: GrowthRepository) : GrowthRepository by base {
    private val profiles = mutableMapOf<Long, GrowthProfile>()
    private val masteries = mutableMapOf<Pair<Long, String>, KeywordMastery>()
    private val signals = mutableMapOf<Triple<Long, String, String>, GrowthSignal>()
    private val evidence = mutableMapOf<List<Any>, GrowthEvidence>()
    private val activities = mutableMapOf<Triple<Long, String, String>, GrowthActivity>()
    private val metrics = mutableMapOf<Long, List<GrowthMetric>>()
    override fun profile(userId: Long) = profiles[userId] ?: base.profile(userId)
    override fun saveProfile(value: GrowthProfile) { profiles[value.userId] = value }
    override fun mastery(userId: Long, key: String) = masteries[userId to key] ?: base.mastery(userId, key)
    override fun saveMastery(value: KeywordMastery) { masteries[value.userId to value.canonicalKey] = value }
    override fun masteries(userId: Long, keys: List<String>?, limit: Int): List<KeywordMastery> {
        val values = base.masteries(userId, keys, limit + masteries.size).associateBy { it.canonicalKey }.toMutableMap()
        masteries.values.filter { it.userId == userId && (keys == null || it.canonicalKey in keys) }.forEach { values[it.canonicalKey] = it }
        return values.values.sortedBy { it.score }.take(limit)
    }
    override fun signal(userId: Long, type: String, key: String) = signals[Triple(userId, type, key)] ?: base.signal(userId, type, key)
    override fun saveSignal(value: GrowthSignal) { signals[Triple(value.userId, value.type, value.key)] = value }
    override fun signals(userId: Long, type: String, limit: Int): List<GrowthSignal> {
        val values = base.signals(userId, type, limit + signals.size).associateBy { it.key }.toMutableMap()
        signals.values.filter { it.userId == userId && it.type == type }.forEach { values[it.key] = it }
        return values.values.sortedByDescending { it.occurrenceCount }.take(limit)
    }
    override fun evidence(userId: Long, source: String, pattern: String, direction: String) =
        evidence[listOf(userId, source, pattern, direction)] ?: base.evidence(userId, source, pattern, direction)
    override fun saveEvidence(value: GrowthEvidence) { evidence[listOf(value.userId, value.source, value.patternKey, value.direction)] = value }
    override fun activity(userId: Long, source: String, referenceId: String) =
        activities[Triple(userId, source, referenceId)] ?: base.activity(userId, source, referenceId)
    override fun saveActivity(value: GrowthActivity): GrowthActivity {
        val saved = if (value.id == 0L) value.copy(id = -(activities.size.toLong() + 1)) else value
        activities[Triple(value.userId, value.source, value.referenceId)] = saved
        return saved
    }
    override fun metrics(activityId: Long) = metrics[activityId] ?: base.metrics(activityId)
    override fun replaceMetrics(activityId: Long, metrics: List<GrowthMetric>) { this.metrics[activityId] = metrics.toList() }
}
