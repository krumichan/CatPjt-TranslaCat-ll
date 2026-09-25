package jp.co.translacat.languagelearning.features.leveltest.domain.repository

import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import java.time.LocalDateTime

/** 한 레벨 테스트 aggregate의 저장 계약이다. 반환값은 트랜잭션 밖에서도 안전한 불변 모델이다. */
internal interface LevelTestRepository {
    fun claimMaintenance(token: String, now: LocalDateTime, until: LocalDateTime): Boolean
    fun releaseMaintenance(token: String): Boolean
    fun ownsMaintenance(token: String, now: LocalDateTime): Boolean
    fun session(id: Long): LevelSession?
    fun sessionByKey(userId: Long, key: String): LevelSession?
    fun sessions(userId: Long): List<LevelSession>
    fun saveSession(value: LevelSession): LevelSession
    fun baseline(userId: Long): LevelBaseline?
    fun saveBaseline(value: LevelBaseline)
    fun items(sessionId: Long): List<LevelItem>
    fun item(id: Long): LevelItem?
    fun itemAt(sessionId: Long, number: Int): LevelItem?
    fun saveItem(value: LevelItem): LevelItem
    fun deleteUnansweredItem(id: Long)
    fun submission(itemId: Long): LevelSubmission?
    fun saveSubmission(value: LevelSubmission): LevelSubmission
    fun evaluation(responseId: Long): LevelEvaluation?
    fun saveEvaluation(value: LevelEvaluation)
    fun clearEvaluation(responseId: Long)
    fun candidate(sessionId: Long, number: Int, band: Int): LevelCandidate?
    fun saveCandidate(value: LevelCandidate): LevelCandidate
    fun queuedCandidates(limit: Int): List<LevelCandidate>
    fun pool(id: Long): LevelPoolQuestion?
    fun poolQuestions(origin: String, learning: String): List<LevelPoolQuestion>
    fun savePool(value: LevelPoolQuestion): LevelPoolQuestion
    fun recentItems(userId: Long, learning: String, since: LocalDateTime): List<LevelItem>
    fun audio(key: String): LevelAudio?
    fun saveAudio(value: LevelAudio)
    fun expiredAudio(now: LocalDateTime, limit: Int): List<LevelAudio>
}
