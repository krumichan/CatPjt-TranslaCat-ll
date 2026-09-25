package jp.co.translacat.languagelearning.features.growth.domain.repository

import jp.co.translacat.languagelearning.features.growth.domain.model.*
import java.time.LocalDate

/** 호출 트랜잭션 소유 repository. 구현 객체를 suspend/HTTP 경계 밖으로 내보내지 않는다. */
internal interface GrowthRepository {
    fun profile(userId: Long): GrowthProfile?
    fun saveProfile(value: GrowthProfile)
    fun mastery(userId: Long, key: String): KeywordMastery?
    fun masteries(userId: Long, keys: List<String>?, limit: Int): List<KeywordMastery>
    fun saveMastery(value: KeywordMastery)
    fun signal(userId: Long, type: String, key: String): GrowthSignal?
    fun signals(userId: Long, type: String, limit: Int): List<GrowthSignal>
    fun saveSignal(value: GrowthSignal)
    fun evidence(userId: Long, source: String, pattern: String, direction: String): GrowthEvidence?
    fun evidenceList(userId: Long, source: String?, limit: Int): List<GrowthEvidence>
    fun saveEvidence(value: GrowthEvidence)
    fun activity(userId: Long, source: String, referenceId: String): GrowthActivity?
    fun activities(userId: Long, source: String?, from: LocalDate, to: LocalDate, afterId: Long, limit: Int): List<GrowthActivity>
    fun saveActivity(value: GrowthActivity): GrowthActivity
    fun metrics(activityId: Long): List<GrowthMetric>
    fun replaceMetrics(activityId: Long, metrics: List<GrowthMetric>)
}
