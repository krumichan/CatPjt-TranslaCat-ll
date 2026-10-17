package jp.co.translacat.languagelearning.features.growth.domain.repository

import jp.co.translacat.languagelearning.features.growth.domain.model.*
import java.time.LocalDate

/** 호출 트랜잭션 소유 repository. 구현 객체를 suspend/HTTP 경계 밖으로 내보내지 않는다. */
internal interface GrowthRepository {
    /** 늦게 완료된 작업은 현재 설정 대신 생성 당시의 언어쌍에 성장 결과를 반영한다. */
    fun scoped(originLanguage: String?, learningLanguage: String?): GrowthRepository
    fun learningEvidence(filter: LearningEvidenceFilter): List<LearningEvidenceRecord>
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
    fun activities(
        userId: Long, source: String?, from: LocalDate, to: LocalDate, afterId: Long, limit: Int,
    ): List<GrowthActivity>

    fun saveActivity(value: GrowthActivity): GrowthActivity
    /** 공식 점수와 metric이 없는 owner 활동만 삭제하고 같은 트랜잭션에서 조회 revision을 갱신한다. */
    fun deleteUnscoredActivity(userId: Long, source: String, referenceId: String): Boolean
    fun activityRevision(userId: Long): Long
    fun metrics(activityId: Long): List<GrowthMetric>
    fun replaceMetrics(activityId: Long, metrics: List<GrowthMetric>)
}
