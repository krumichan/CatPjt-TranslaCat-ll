package jp.co.translacat.languagelearning.features.listening.application

import jp.co.translacat.languagelearning.features.growth.domain.repository.GrowthRepository
import jp.co.translacat.languagelearning.features.listening.domain.model.*
import jp.co.translacat.languagelearning.shared.diversity.GenerationFingerprintRepository
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.LocalDateTime

internal interface ListeningUnitOfWork {
    suspend fun <T> read(block: ListeningTransaction.() -> T): T
    suspend fun <T> write(userId: Long, block: ListeningTransaction.() -> T): T
}

/** 외부 모델·오디오 실행을 호출하지 않는 짧은 사용자 잠금 트랜잭션이다. */
internal interface ListeningTransaction {
    val nowUtc: LocalDateTime
    val growth: GrowthRepository
    val fingerprints: GenerationFingerprintRepository
    fun allocateId(): Long
    fun set(userId: Long, id: Long): ListeningSetState?
    fun sets(userId: Long, date: LocalDate? = null): List<ListeningSetState>
    fun insertSet(value: ListeningSetState)
    fun updateSet(value: ListeningSetState): ListeningSetState
    fun session(userId: Long, id: Long): ListeningSessionState?
    fun sessions(userId: Long): List<ListeningSessionState>
    fun insertSession(value: ListeningSessionState)
    fun updateSession(value: ListeningSessionState): ListeningSessionState
    fun rememberSelection(userId: Long, expectedRevision: LocalDateTime, tasks: List<ListeningTaskType>)
    fun enqueue(userId: Long, aggregateId: Long, type: String, key: String, payload: JsonObject): Long
    fun jobForKey(userId: Long, key: String): ListeningJob?
    fun hasActiveJob(userId: Long, aggregateId: Long, type: String): Boolean
    fun recoverable(limit: Int): List<ListeningJob>
    fun claim(userId: Long, id: Long, token: String, leaseUntil: LocalDateTime): ListeningJob?
    fun finish(claim: ListeningJob, errorCode: String? = null): Boolean
    fun renew(claim: ListeningJob): Boolean
    fun release(claim: ListeningJob)
    fun audioUsersToExpire(limit: Int): List<Long>
    fun fail(
        claim: ListeningJob, errorCode: String, retryable: Boolean, retryLimit: Int,
        retryAfter: java.time.Duration = java.time.Duration.ofSeconds(1),
    ): ListeningJobFailure

    fun insertAudio(value: ListeningAudioState)
    fun audio(userId: Long, id: Long): ListeningAudioState?
    fun extendAudio(userId: Long, id: Long, until: LocalDateTime)
    fun deleteAudio(userId: Long, id: Long)
    fun expireAudio(userId: Long): Int
    fun metricHistory(userId: Long, language: String): List<ListeningMetricHistoryState>
    fun saveMetricHistory(value: ListeningMetricHistoryState)
    fun recommendations(userId: Long, language: String): List<ListeningRecommendationState>
    fun recommendation(userId: Long, id: Long): ListeningRecommendationState?
    fun saveRecommendation(value: ListeningRecommendationState)
}
