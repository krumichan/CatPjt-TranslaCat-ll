package jp.co.translacat.languagelearning.features.autopractice.infrastructure

import jp.co.translacat.languagelearning.features.autopractice.application.*
import jp.co.translacat.languagelearning.features.autopractice.domain.*
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** LL 트랜잭션 안에서만 활성 플랜, 일별 배정 및 작업 lease를 읽고 쓴다. */
internal class JdbcAutoPracticeStore(
    private val transactions: JdbcTransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
    private val lockActiveOwner: (Long, LocalDateTime) -> Unit,
) : AutoPracticeStore, AutoPracticePublication {
    override suspend fun <T> read(block: AutoPracticeTransaction.() -> T): T =
        transactions.read { AutoPracticeRecords().block() }

    override suspend fun <T> write(block: AutoPracticeTransaction.() -> T): T =
        transactions.write { AutoPracticeRecords().block() }

    /** 저장 순서가 엇갈려도 한 사용자에게 활성 플랜과 학습일별 배정을 한 번만 기록한다. */
    override suspend fun <T> writeOwner(
        owner: Long,
        now: LocalDateTime,
        block: AutoPracticeTransaction.() -> T,
    ): T = write {
        lockActiveOwner(owner, now)
        block()
    }

    /** 同じ owner ロック下で、最初の公開可能な問題と当日回の確定を同時に保存する。 */
    override fun markPlayable(owner: Long, jobId: Long) {
        val records = AutoPracticeRecords()
        val job = records.job(owner, jobId) ?: return
        val cutoff = job.rolloverAt?.let(Instant::parse) ?: return
        if (clock.instant() < cutoff) records.keepManualRound(owner, job)
    }

    /** ネイティブ ID 直指定でも予約の公開時刻を確認する。 */
    override fun visibleDate(owner: Long, jobId: Long, now: Instant): LocalDate? {
        val records = AutoPracticeRecords()
        val job = records.job(owner, jobId) ?: return null
        if (!AutoPracticePolicy.isVisible(job, now)) return null
        val date = LocalDate.parse(job.learningDate)
        if (job.availableAt == null && job.requestedDate == null) {
            // V043 の既存予約は公開時刻を持たないため、設定の学習日で判定する。
            val zone = records.learnerZone(owner) ?: return null
            if (date > now.atZone(zone).toLocalDate()) return null
        }
        return date
    }
}

/** 호출 시점을 소유한 짧은 JDBC 트랜잭션의 준비된 문장만 사용한다. */
private class AutoPracticeRecords : AutoPracticeTransaction {
    private val json = Json { encodeDefaults = true }
    private val connection = TransactionManager.current().connection.connection as Connection

    override fun plans(user: Long): List<AutoPracticePlan> =
        query("SELECT content_json FROM language_learning_auto_practice_plan WHERE user_id=? ORDER BY id", user) {
            json.decodeFromString(it.getString(1))
        }

    override fun plan(user: Long, id: String): AutoPracticeSavedPlan? = query(
        "SELECT * FROM language_learning_auto_practice_plan WHERE user_id=? AND id=?",
        user, id, row = ::savedPlan,
    ).singleOrNull()

    override fun active(user: Long): AutoPracticeSavedPlan? = query(
        "SELECT * FROM language_learning_auto_practice_plan WHERE user_id=? AND active_slot=1",
        user, row = ::savedPlan,
    ).singleOrNull()

    override fun due(now: LocalDateTime): List<AutoPracticeSavedPlan> = query(
        """SELECT * FROM language_learning_auto_practice_plan WHERE active_slot=1 AND next_check_at<=?
            ORDER BY next_check_at,id LIMIT 100""",
        now, row = ::savedPlan,
    )

    override fun writePlan(user: Long, plan: AutoPracticePlan, activatedAt: LocalDateTime?, next: LocalDateTime?, update: Boolean) {
        val slot = 1.takeIf { plan.enabled }
        val payload = json.encodeToString(plan)
        if (update) {
            execute(
                """UPDATE language_learning_auto_practice_plan
                    SET revision=?,active_slot=?,activated_at=?,next_check_at=?,content_json=?
                    WHERE user_id=? AND id=?""",
                plan.revision, slot, activatedAt, next, payload, user, plan.id,
            )
        } else {
            execute(
                """INSERT INTO language_learning_auto_practice_plan
                    (id,user_id,revision,origin_language,learning_language,active_slot,activated_at,next_check_at,content_json)
                    VALUES (?,?,?,?,?,?,?,?,?)""",
                plan.id, user, plan.revision, plan.originLanguage, plan.learningLanguage, slot, activatedAt, next, payload,
            )
        }
    }

    override fun removePlan(user: Long, id: String): Boolean =
        execute("DELETE FROM language_learning_auto_practice_plan WHERE user_id=? AND id=?", user, id) == 1

    override fun defer(user: Long, id: String, next: LocalDateTime) {
        execute(
            "UPDATE language_learning_auto_practice_plan SET next_check_at=? WHERE user_id=? AND id=?",
            next, user, id,
        )
    }

    override fun limits(): Map<AutoPracticeArea, Int> {
        val configured = query("SELECT area,daily_max FROM language_learning_auto_practice_limit") {
            AutoPracticeArea.valueOf(it.getString(1)) to it.getInt(2)
        }.toMap()
        return AutoPracticeArea.entries.associateWith { configured[it] ?: AutoPracticePolicy.defaultAreaLimit }
    }

    override fun saveLimits(limits: Map<AutoPracticeArea, Int>) {
        limits.forEach { (area, maximum) ->
            execute(
                """INSERT INTO language_learning_auto_practice_limit (area,daily_max) VALUES (?,?)
                    ON DUPLICATE KEY UPDATE daily_max=?""",
                area.name, maximum, maximum,
            )
        }
    }

    /** 학습일·언어쌍·유형이 같으면 플랜 변경과 재조회에도 원래 작업을 유지한다. */
    override fun reserve(
        user: Long, plan: AutoPracticePlan, date: LocalDate, now: LocalDateTime,
        manual: Boolean, rolloverAt: LocalDateTime?, availableAt: LocalDateTime?,
    ) {
        plan.areas.forEach { (area, quantity) ->
            if (quantity.total == 0L) return@forEach
            execute(
                """INSERT INTO language_learning_auto_practice_job
                    (user_id,plan_id,plan_revision,origin_language,learning_language,learning_date,area,quantity_json,
                    state,next_attempt_at,requested_date,rollover_at,available_at)
                    VALUES (?,?,?,?,?,?,?,?,'PENDING',?,?,?,?)
                    ON DUPLICATE KEY UPDATE id=id""",
                user, plan.id, plan.revision, plan.originLanguage, plan.learningLanguage,
                date, area.name, json.encodeToString(quantity), now, date.takeIf { manual }, rolloverAt, availableAt,
            )
        }
    }

    override fun job(user: Long, id: Long): AutoPracticeJob? = query(
        "SELECT * FROM language_learning_auto_practice_job WHERE user_id=? AND id=?",
        user, id, row = ::queuedJob,
    ).singleOrNull()?.job

    /** 公開メタデータ追加前の予約で使われていた学習者の時間帯を読む。 */
    fun learnerZone(user: Long): ZoneId? = query(
        "SELECT timezone FROM language_learning_user_setting WHERE user_id=?", user,
    ) { ZoneId.of(it.getString(1)) }.singleOrNull()

    override fun manualRound(user: Long, plan: AutoPracticePlan, date: LocalDate): List<AutoPracticeJob> = query(
        """SELECT * FROM language_learning_auto_practice_job WHERE user_id=? AND origin_language=?
            AND learning_language=? AND requested_date=? ORDER BY id""",
        user, plan.originLanguage, plan.learningLanguage, date, row = ::queuedJob,
    ).map { it.job }

    /** 予約 ID とネイティブの生成日を保持し、同じ作業を公開日の一意キーへ移す。 */
    override fun settleManual(user: Long, plan: AutoPracticePlan, date: LocalDate, availableAt: LocalDateTime?) {
        execute(
            """UPDATE language_learning_auto_practice_job
                SET learning_date=?,available_at=?,rollover_at=NULL
                WHERE user_id=? AND origin_language=? AND learning_language=? AND requested_date=?
                AND rollover_at IS NOT NULL""",
            if (availableAt == null) date else date.plusDays(1), availableAt,
            user, plan.originLanguage, plan.learningLanguage, date,
        )
    }

    /** 古い計画の変更・無効化が未公開状態を残さないようにする。 */
    override fun keepManualRounds(user: Long, before: LocalDate?) {
        if (before == null) {
            execute(
                "UPDATE language_learning_auto_practice_job SET rollover_at=NULL WHERE user_id=? AND rollover_at IS NOT NULL",
                user,
            )
        } else {
            execute(
                """UPDATE language_learning_auto_practice_job SET rollover_at=NULL WHERE user_id=?
                    AND requested_date<? AND rollover_at IS NOT NULL""",
                user, before,
            )
        }
    }

    /** すでに問題を公開できる回は、以後の自動準備でも当日のままにする。 */
    fun keepManualRound(user: Long, job: AutoPracticeJob) {
        execute(
            """UPDATE language_learning_auto_practice_job SET rollover_at=NULL WHERE user_id=? AND origin_language=?
                AND learning_language=? AND requested_date=? AND rollover_at IS NOT NULL""",
            user, job.originLanguage, job.learningLanguage, LocalDate.parse(checkNotNull(job.requestedDate)),
        )
    }

    override fun assignments(user: Long, origin: String, learning: String, from: LocalDate, through: LocalDate): List<AutoPracticeJob> =
        query(
            """SELECT * FROM language_learning_auto_practice_job WHERE user_id=? AND origin_language=?
                AND learning_language=? AND learning_date BETWEEN ? AND ? ORDER BY learning_date DESC, id""",
            user, origin, learning, from, through, row = ::queuedJob,
        ).map { it.job }

    override fun pending(now: LocalDateTime): List<AutoPracticeQueuedJob> = query(
        """SELECT * FROM language_learning_auto_practice_job
            WHERE state IN ('PENDING','CLAIMED') AND next_attempt_at<=? ORDER BY next_attempt_at,id LIMIT 32""",
        now, row = ::queuedJob,
    )

    /** 여러 실행기 중 조건에 맞는 한 작업만 점유하고 만료 시 같은 native 식별자로 재시도한다. */
    override fun claim(job: AutoPracticeQueuedJob, now: LocalDateTime, lease: String): Boolean = execute(
        """UPDATE language_learning_auto_practice_job SET state='CLAIMED',lease_token=?,next_attempt_at=?,attempts=attempts+1
            WHERE user_id=? AND id=? AND state IN ('PENDING','CLAIMED') AND next_attempt_at<=? AND attempts=?""",
        lease, now.plusMinutes(10), job.userId, job.job.id, now, job.attempts,
    ) == 1

    override fun finish(
        job: AutoPracticeQueuedJob, lease: String, state: String, next: LocalDateTime,
        resourceId: String?, reason: String?,
    ) {
        execute(
            """UPDATE language_learning_auto_practice_job
                SET state=?,next_attempt_at=?,resource_id=?,reason=?,lease_token=NULL
                WHERE user_id=? AND id=? AND lease_token=?""",
            state, next, resourceId, reason, job.userId, job.job.id, lease,
        )
    }

    override fun placement(user: Long, origin: String, learning: String): LocalDateTime? = query(
        """SELECT MIN(completed_at) FROM language_learning_level_test_session WHERE user_id=? AND origin_language=?
            AND learning_language=? AND status='COMPLETED' AND session_type IN ('INITIAL','SELF_SELECTED')""",
        user, origin, learning,
    ) { it.getObject(1, LocalDateTime::class.java) }.singleOrNull()

    /** 생성일 대신 실제 제출 시각을 조회한다. 건너뛰기·임시 답안·세션 열기를 제외한다. */
    override fun submissions(
        user: Long, origin: String, learning: String, since: LocalDateTime, until: LocalDateTime,
    ): List<LocalDateTime> {
        val sqls = listOf(
            """SELECT a.submitted_at FROM language_learning_writing_answer a
                JOIN language_learning_daily_item i ON i.id=a.daily_item_id
                JOIN language_learning_daily_set s ON s.id=i.daily_set_id
                WHERE s.user_id=? AND JSON_UNQUOTE(JSON_EXTRACT(s.snapshot_json,'$.originLanguage'))=?
                AND JSON_UNQUOTE(JSON_EXTRACT(s.snapshot_json,'$.learningLanguage'))=?
                AND a.submitted_at>=? AND a.submitted_at<?""",
            """SELECT a.submitted_at FROM language_learning_curated_writing_answer a
                JOIN language_learning_curated_writing_item i ON i.id=a.item_id
                JOIN language_learning_curated_writing_set s ON s.id=i.set_id
                WHERE s.user_id=? AND s.origin_language=? AND s.learning_language=?
                AND a.submitted_at>=? AND a.submitted_at<?""",
            """SELECT a.submitted_at FROM language_learning_practice_attempt a
                JOIN language_learning_practice_question q ON q.id=a.question_id
                JOIN language_learning_practice_set s ON s.id=q.practice_set_id
                WHERE s.user_id=? AND JSON_UNQUOTE(JSON_EXTRACT(s.request_json,'$.originLanguage'))=?
                AND JSON_UNQUOTE(JSON_EXTRACT(s.request_json,'$.learningLanguage'))=?
                AND a.submitted_at>=? AND a.submitted_at<?""",
            """SELECT t.completed_at FROM language_learning_speaking_turn t
                JOIN language_learning_speaking_session s ON s.id=t.session_id
                WHERE s.user_id=? AND JSON_UNQUOTE(JSON_EXTRACT(s.snapshot_json,'$.originLanguage'))=?
                AND JSON_UNQUOTE(JSON_EXTRACT(s.snapshot_json,'$.learningLanguage'))=?
                AND t.completed_at>=? AND t.completed_at<? AND t.status='COMPLETED'""",
            """SELECT a.submitted_at FROM language_learning_listening_session n
                JOIN language_learning_listening_set s ON s.id=n.set_id
                JOIN JSON_TABLE(n.state_json, '$.attempts[*]' COLUMNS(
                    submitted_at DATETIME(6) PATH '$.submittedAt' NULL ON ERROR,
                    attempt_status VARCHAR(30) PATH '$.status')) AS a
                WHERE n.user_id=? AND JSON_UNQUOTE(JSON_EXTRACT(s.state_json,'$.originLanguage'))=?
                AND s.learning_language=? AND a.submitted_at>=? AND a.submitted_at<? AND a.attempt_status<>'SKIPPED'""",
        )
        return sqls.flatMap { statement ->
            query(statement, user, origin, learning, since, until) { it.getObject(1, LocalDateTime::class.java) }
        }
    }

    private fun savedPlan(row: ResultSet): AutoPracticeSavedPlan = AutoPracticeSavedPlan(
        row.getLong("user_id"), json.decodeFromString(row.getString("content_json")),
        row.getObject("activated_at", LocalDateTime::class.java),
    )

    private fun queuedJob(row: ResultSet): AutoPracticeQueuedJob = AutoPracticeQueuedJob(
        row.getLong("user_id"),
        AutoPracticeJob(
            row.getLong("id"), row.getString("plan_id"), row.getLong("plan_revision"),
            row.getString("origin_language"), row.getString("learning_language"), row.getString("learning_date"),
            AutoPracticeArea.valueOf(row.getString("area")), json.decodeFromString(row.getString("quantity_json")),
            row.getString("state"), row.getString("resource_id"), row.getString("reason"),
            row.getString("requested_date"), instant(row, "rollover_at"), instant(row, "available_at"),
        ),
        row.getInt("attempts"),
    )

    /** DB の UTC 時刻をクライアントが解釈できるオフセット付き時刻として返す。 */
    private fun instant(row: ResultSet, column: String): String? =
        row.getObject(column, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)?.toString()

    /** 조회와 바인딩을 호출 트랜잭션 안에서 끝내고 JDBC 객체를 밖으로 넘기지 않는다. */
    private fun <T> query(sql: String, vararg arguments: Any?, row: (ResultSet) -> T): List<T> =
        connection.prepareStatement(sql).use { statement ->
            statement.queryTimeout = 10
            arguments.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(row(rows)) } }
        }

    /** 호출자가 소유자 잠금과 회차 상태를 확인한 뒤 변경 행 수로 성공을 판정한다. */
    private fun execute(sql: String, vararg arguments: Any?): Int =
        connection.prepareStatement(sql).use { statement ->
            statement.queryTimeout = 10
            arguments.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
}
