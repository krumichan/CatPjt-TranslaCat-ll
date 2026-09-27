package jp.co.translacat.languagelearning.features.practice.application

import jp.co.translacat.languagelearning.features.growth.domain.repository.GrowthRepository
import jp.co.translacat.languagelearning.features.practice.domain.*
import java.time.LocalDate
import java.time.LocalDateTime

internal interface PracticeUnitOfWork {
    suspend fun <T> read(block: PracticeTransaction.() -> T): T
    suspend fun <T> write(userId: Long, block: PracticeTransaction.() -> T): T
}

internal interface PracticeTransaction {
    val records: PracticeRepository
    val growth: GrowthRepository
    val nowUtc: LocalDateTime
}

/** 쓰기는 learner 잠금 안에서 실행해 생성·제출·완료를 사용자 단위로 직렬화한다. */
internal interface PracticeRepository {
    fun find(userId: Long, setId: Long): PracticeSet?
    fun today(userId: Long, date: LocalDate, domain: PracticeDomain): List<PracticeSet>
    fun range(userId: Long, from: LocalDate, to: LocalDate): List<PracticeSet>
    fun recentScores(userId: Long, domain: PracticeDomain, mode: String): List<Double>
    fun recentMistakes(userId: Long, domain: PracticeDomain): List<PracticeQuestionContent>
    fun save(value: PracticeSet): PracticeSet
    fun questions(setId: Long): List<PracticeQuestion>
    fun question(userId: Long, questionId: Long): PracticeQuestion?
    fun addQuestion(setId: Long, content: PracticeQuestionContent): PracticeQuestion
    fun attempts(setId: Long): List<PracticeAttempt>
    fun addAttempt(value: PracticeAttempt): PracticeAttempt
    fun metrics(setId: Long): List<PracticeMetric>
    fun saveMetrics(setId: Long, values: List<PracticeMetric>)
    fun masteries(userId: Long): List<VocabularyMastery>
    fun saveMastery(value: VocabularyMastery)
    fun recoverable(now: LocalDateTime, limit: Int): List<Pair<Long, Long>>
}
