package jp.co.translacat.languagelearning.features.autopractice.application

import jp.co.translacat.languagelearning.features.autopractice.domain.*
import java.time.LocalDate
import java.time.LocalDateTime

/** 트랜잭션 블록은 저장 작업만 수행하며 결과에 JDBC 객체를 포함하지 않는다. */
internal interface AutoPracticeStore {
    suspend fun <T> read(block: AutoPracticeTransaction.() -> T): T
    suspend fun <T> write(block: AutoPracticeTransaction.() -> T): T
    suspend fun <T> writeOwner(owner: Long, now: LocalDateTime, block: AutoPracticeTransaction.() -> T): T
}

internal data class AutoPracticeSavedPlan(val userId: Long, val plan: AutoPracticePlan, val activatedAt: LocalDateTime?)
internal data class AutoPracticeQueuedJob(val userId: Long, val job: AutoPracticeJob, val attempts: Int)

/** 일일 배정 고유 키와 실행 lease는 이 트랜잭션에 속한다. */
internal interface AutoPracticeTransaction {
    fun plans(user: Long): List<AutoPracticePlan>
    fun plan(user: Long, id: String): AutoPracticeSavedPlan?
    fun active(user: Long): AutoPracticeSavedPlan?
    fun due(now: LocalDateTime): List<AutoPracticeSavedPlan>
    fun writePlan(user: Long, plan: AutoPracticePlan, activatedAt: LocalDateTime?, next: LocalDateTime?, update: Boolean)
    fun removePlan(user: Long, id: String): Boolean
    fun defer(user: Long, id: String, next: LocalDateTime)
    fun limits(): Map<AutoPracticeArea, Int>
    fun saveLimits(limits: Map<AutoPracticeArea, Int>)
    fun reserve(user: Long, plan: AutoPracticePlan, date: LocalDate, now: LocalDateTime)
    fun assignments(user: Long, origin: String, learning: String, from: LocalDate, through: LocalDate): List<AutoPracticeJob>
    fun pending(now: LocalDateTime): List<AutoPracticeQueuedJob>
    fun claim(job: AutoPracticeQueuedJob, now: LocalDateTime, lease: String): Boolean
    fun finish(
        job: AutoPracticeQueuedJob, lease: String, state: String,
        next: LocalDateTime, resourceId: String?, reason: String?,
    )
    fun placement(user: Long, origin: String, learning: String): LocalDateTime?
    fun submissions(user: Long, origin: String, learning: String, since: LocalDateTime, until: LocalDateTime): List<LocalDateTime>
}
