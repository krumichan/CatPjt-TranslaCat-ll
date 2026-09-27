package jp.co.translacat.languagelearning.features.speaking.application

import jp.co.translacat.languagelearning.features.growth.domain.repository.GrowthRepository
import jp.co.translacat.languagelearning.features.speaking.domain.*
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.LocalDateTime

internal interface SpeakingUnitOfWork {
    suspend fun <T> read(block: SpeakingTransaction.() -> T): T
    suspend fun <T> write(userId: Long, block: SpeakingTransaction.() -> T): T
    suspend fun <T> catalogWrite(block: SpeakingTransaction.() -> T): T
}

internal interface SpeakingTransaction {
    val records: SpeakingRepository
    val growth: GrowthRepository
    val nowUtc: LocalDateTime
}

/** 모든 사용자 쓰기는 learner 잠금 안에서 수행하고 외부 모델·오디오 I/O는 트랜잭션 밖에서 실행한다. */
internal interface SpeakingRepository {
    fun topics(): List<SpeakingTopic>
    fun topic(id: Long): SpeakingTopic?
    fun saveTopic(value: SpeakingTopic): SpeakingTopic
    fun session(userId: Long, sessionId: Long): SpeakingSessionRecord?
    fun sessionByIdempotency(userId: Long, key: String): SpeakingSessionRecord?
    fun active(userId: Long): SpeakingSessionRecord?
    fun sessions(userId: Long, from: LocalDate, to: LocalDate): List<SpeakingSessionRecord>
    fun expirable(now: LocalDateTime, limit: Int): List<Pair<Long, Long>>
    fun saveSession(value: SpeakingSessionRecord): SpeakingSessionRecord
    fun turns(sessionId: Long): List<SpeakingTurnRecord>
    fun turn(sessionId: Long, turnId: Long): SpeakingTurnRecord?
    fun expiredTurns(now: LocalDateTime, limit: Int): List<Triple<Long, Long, Long>>
    fun saveTurn(value: SpeakingTurnRecord): SpeakingTurnRecord
    fun job(sessionId: Long, problemIndex: Int): SpeakingJobRecord?
    fun saveJob(value: SpeakingJobRecord): SpeakingJobRecord
    fun dueJobs(now: LocalDateTime, limit: Int): List<Triple<Long, Long, Int>>
    fun result(sessionId: Long, problemIndex: Int): SpeakingResultRecord?
    fun results(sessionId: Long): List<SpeakingResultRecord>
    fun saveResult(value: SpeakingResultRecord)
    fun audio(sessionId: Long, turnId: Long?, role: String): SpeakingAudioRecord?
    fun audioById(userId: Long, audioId: Long): SpeakingAudioRecord?
    fun audioOwner(audioId: Long): Long?
    fun saveAudio(value: SpeakingAudioRecord): SpeakingAudioRecord
    fun dueAudio(now: LocalDateTime, limit: Int, pendingOnly: Boolean = false): List<SpeakingAudioRecord>
    fun addUsage(sessionId: Long, turnId: Long?, usage: JsonObject, manualRetryAttempt: Int, now: LocalDateTime)
    fun report(userId: Long, reference: String): SpeakingSttReportRecord?
    fun reportById(userId: Long, reportId: Long): SpeakingSttReportRecord?
    fun saveReport(value: SpeakingSttReportRecord): SpeakingSttReportRecord
}
