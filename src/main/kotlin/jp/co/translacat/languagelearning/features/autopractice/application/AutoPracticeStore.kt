package jp.co.translacat.languagelearning.features.autopractice.application

import jp.co.translacat.languagelearning.features.autopractice.domain.*
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
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

/** 既存の出題・回答トランザクション内で自動出題回の公開を扱う。 */
internal interface AutoPracticePublication {
    /** 学習者に見せられる最初の問題が保存された時点で、同じ回を当日に固定する。 */
    fun markPlayable(owner: Long, jobId: Long)

    /** 公開済み回の日付を取得し、未公開回の直接参照を拒否する。 */
    fun visibleDate(owner: Long, jobId: Long, now: Instant): LocalDate?

    /** 参照先が未公開なら本文・音声・解答を処理しない。 */
    fun openedDate(owner: Long, jobId: Long, now: Instant): LocalDate =
        visibleDate(owner, jobId, now) ?: autoPracticeNotOpen()
}

/** ネイティブの既存スナップショットに保存された予約 ID を読む。 */
internal fun JsonObject.autoPracticeReference(): Long? =
    (get("autoPracticeJobId") as? JsonPrimitive)?.longOrNull

/** 自動出題前に作られたスナップショットにも同じ読み取り口を使う。 */
internal fun String.autoPracticeReference(): Long? =
    if ("\"autoPracticeJobId\"" in this) (Json.parseToJsonElement(this) as? JsonObject)?.autoPracticeReference() else null

/** 公開前の直接アクセスでも同じエラーコードを返す。 */
internal fun autoPracticeNotOpen(): Nothing =
    throw LearningBusinessException("AUTO_PRACTICE_NOT_OPEN", "학습 문제가 아직 공개되지 않았습니다.")

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
    fun reserve(
        user: Long, plan: AutoPracticePlan, date: LocalDate, now: LocalDateTime,
        manual: Boolean = false, rolloverAt: LocalDateTime? = null, availableAt: LocalDateTime? = null,
    )
    fun job(user: Long, id: Long): AutoPracticeJob?
    fun manualRound(user: Long, plan: AutoPracticePlan, date: LocalDate): List<AutoPracticeJob>
    fun settleManual(user: Long, plan: AutoPracticePlan, date: LocalDate, availableAt: LocalDateTime? = null)
    fun keepManualRounds(user: Long, before: LocalDate? = null)
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
