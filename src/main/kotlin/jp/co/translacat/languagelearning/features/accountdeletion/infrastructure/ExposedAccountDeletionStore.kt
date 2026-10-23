package jp.co.translacat.languagelearning.features.accountdeletion.infrastructure

import jp.co.translacat.languagelearning.features.accountdeletion.application.*
import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.repository.ExposedLearnerRepository
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

internal class ExposedAccountDeletionStore(
    private val transactions: JdbcTransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
) : AccountDeletionStore {
    private val sql = AccountDeletionSql
    private fun now() = Timestamp.from(clock.instant())
    private fun lock(user: Long) = ExposedLearnerRepository {}.ensureAndLock(
        user, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
    )

    override suspend fun ready(): Boolean = transactions.read {
        // migration 누락은 실제 테이블 조회로 거부한다. 개인 사용자 행은 만들지 않는다.
        sql.count("SELECT COUNT(*) FROM language_learning_account_deletion WHERE 1=0")
        sql.count("SELECT COUNT(*) FROM language_learning_account_deletion_object WHERE 1=0")
        sql.count("SELECT COUNT(*) FROM language_learning_audio_write WHERE 1=0")
        true
    }

    override suspend fun isSessionActive(sessionId: String): Boolean {
        val id = sessionId.toLongOrNull()?.takeIf { it > 0 } ?: return false
        return transactions.read {
            sql.count("SELECT COUNT(*) FROM language_learning_speaking_session s " +
                "JOIN language_learning_learner l ON l.user_id=s.user_id WHERE s.id=? AND l.status='ACTIVE'", id) == 1
        }
    }

    override suspend fun isUserAvailable(userId: Long): Boolean = transactions.read {
        // LL 신규 사용자는 아직 learner 행이 없을 수 있다. 기존 tombstone을 가진 경우만 이용을 거부한다.
        val status = sql.query("SELECT status FROM language_learning_learner WHERE user_id=?", userId) { it.getString(1) }.singleOrNull()
        status == null || status == "ACTIVE"
    }

    override suspend fun prepare(userId: Long, operationId: String, requestedAt: Instant): List<String> = transactions.write {
        // 일반 업무 저장과 같은 learner 잠금 아래 계정을 먼저 고정하고 재활성화를 금지한다.
        lock(userId)
        val previous = sql.query("SELECT operation_id FROM language_learning_account_deletion WHERE user_id=? FOR UPDATE", userId) {
            it.getString(1)
        }.singleOrNull()
        if (previous != null && previous != operationId) throw AccountDeletionFailure("ACCOUNT_DELETION_CONFLICT", 409)
        if (previous == null) {
            sql.update("INSERT INTO language_learning_account_deletion(user_id,operation_id,status,requested_at,updated_at) VALUES(?,?,'PENDING',?,?)",
                userId, operationId, Timestamp.from(requestedAt), now())
            sql.update("UPDATE language_learning_learner SET status='DELETION_PENDING',identity_version=identity_version+1,updated_at=? WHERE user_id=?",
                now(), userId)
        }

        // 메타데이터 연결 전의 업로드도 ledger에서 수집한다. 키를 먼저 보관하므로 재시작 뒤에도 정리한다.
        sql.update("INSERT IGNORE INTO language_learning_account_deletion_object(operation_id,store_kind,object_key,completed) " +
            "SELECT ?,'LEVEL_TEST',object_key,FALSE FROM language_learning_level_test_audio WHERE owner_user_id=?", operationId, userId)
        sql.update("INSERT IGNORE INTO language_learning_account_deletion_object(operation_id,store_kind,object_key,completed) " +
            "SELECT ?,'SPEAKING',a.object_key,FALSE FROM language_learning_speaking_audio a " +
            "JOIN language_learning_speaking_session s ON s.id=a.session_id WHERE s.user_id=?", operationId, userId)
        sql.update("INSERT IGNORE INTO language_learning_account_deletion_object(operation_id,store_kind,object_key,completed) " +
            "SELECT ?,store_kind,object_key,FALSE FROM language_learning_audio_write WHERE user_id=?", operationId, userId)
        sql.query("SELECT id FROM language_learning_speaking_session WHERE user_id=?", userId) { it.getLong(1).toString() }
    }

    override suspend fun activeWrites(userId: Long): Int = transactions.read { activeCount(userId) }

    private fun activeCount(userId: Long) = sql.count(
        // 시간 경과만으로 원격 PUT의 미실행/종료를 확정하지 않는다. end 기록 없는 intent는 계속 pending이다.
        "SELECT COUNT(*) FROM language_learning_audio_write WHERE user_id=? AND lease_until IS NOT NULL", userId,
    )

    override suspend fun objects(userId: Long, operationId: String): List<AccountDeletionObject> = transactions.read {
        sql.query("SELECT o.store_kind,o.object_key FROM language_learning_account_deletion_object o " +
            "JOIN language_learning_account_deletion d ON d.operation_id=o.operation_id " +
            "WHERE d.user_id=? AND d.operation_id=? AND o.completed=FALSE ORDER BY o.store_kind,o.object_key LIMIT 100",
            userId, operationId) { AccountDeletionObject(it.getString(1), it.getString(2)) }
    }

    override suspend fun objectDeleted(userId: Long, operationId: String, value: AccountDeletionObject) = transactions.write {
        lock(userId)
        sql.update("UPDATE language_learning_account_deletion_object o JOIN language_learning_account_deletion d " +
            "ON d.operation_id=o.operation_id SET o.completed=TRUE WHERE d.user_id=? AND d.operation_id=? AND o.store_kind=? AND o.object_key=?",
            userId, operationId, value.kind, value.key)
        Unit
    }

    override suspend fun finish(userId: Long, operationId: String): AccountDeletionResponse = transactions.write {
        lock(userId)
        val status = sql.query("SELECT status FROM language_learning_account_deletion WHERE user_id=? AND operation_id=? FOR UPDATE",
            userId, operationId) { it.getString(1) }.singleOrNull()
            ?: throw AccountDeletionFailure("ACCOUNT_DELETION_CONFLICT", 409)
        val pending = maxOf(sql.count("SELECT COUNT(*) FROM language_learning_account_deletion_object WHERE operation_id=? AND completed=FALSE",
            operationId), activeCount(userId))
        if (status == "COMPLETED") return@write AccountDeletionResponse(operationId, userId, status, 0)
        if (pending != 0) return@write AccountDeletionResponse(operationId, userId, "PENDING", pending)

        // 외부 객체가 모두 정리된 뒤 같은 잠금/트랜잭션에서 개인 자식→부모를 지운다.
        purgeAccountLearningData(userId)
        sql.update("DELETE FROM language_learning_audio_write WHERE user_id=?", userId)
        sql.update("DELETE FROM language_learning_account_deletion_object WHERE operation_id=?", operationId)
        sql.update("UPDATE language_learning_learner SET status='DELETED',updated_at=? WHERE user_id=?", now(), userId)
        sql.update("UPDATE language_learning_account_deletion SET status='COMPLETED',updated_at=? WHERE user_id=? AND operation_id=?",
            now(), userId, operationId)
        AccountDeletionResponse(operationId, userId, "COMPLETED", 0)
    }

    override suspend fun beginAudioWrite(userId: Long, value: AccountDeletionObject) = transactions.write {
        lock(userId).requireActive()
        val prior = sql.query("SELECT user_id,lease_until FROM language_learning_audio_write WHERE store_kind=? AND object_key=? FOR UPDATE",
            value.kind, value.key) { it.getLong(1) to it.getTimestamp(2)?.toInstant() }.singleOrNull()
        if (prior != null && prior.first != userId) throw AccountDeletionFailure("AUDIO_OWNER_CONFLICT", 409)
        if (prior?.second != null) throw AccountDeletionFailure("AUDIO_WRITE_BUSY", 409)
        if (prior == null) {
            sql.update("INSERT INTO language_learning_audio_write(store_kind,object_key,user_id,lease_until) VALUES(?,?,?,?)",
                value.kind, value.key, userId, Timestamp.from(clock.instant().plusSeconds(120)))
        } else {
            sql.update("UPDATE language_learning_audio_write SET lease_until=? WHERE user_id=? AND store_kind=? AND object_key=?",
                Timestamp.from(clock.instant().plusSeconds(120)), userId, value.kind, value.key)
        }
        Unit
    }

    override suspend fun endAudioWrite(userId: Long, value: AccountDeletionObject) = transactions.write {
        // tombstone 뒤에도 종료 기록은 가능하다. 일반 업무의 ACTIVE 강제 UoW를 우회하는 전용 경계다.
        lock(userId)
        sql.update("UPDATE language_learning_audio_write SET lease_until=NULL,uncertain_reason=NULL WHERE user_id=? AND store_kind=? AND object_key=?",
            userId, value.kind, value.key)
        Unit
    }

    override suspend fun uncertainAudioWrite(userId: Long, value: AccountDeletionObject) = transactions.write {
        lock(userId)
        sql.update("UPDATE language_learning_audio_write SET uncertain_reason='AUDIO_WRITE_UNCERTAIN' " +
            "WHERE user_id=? AND store_kind=? AND object_key=? AND lease_until IS NOT NULL", userId, value.kind, value.key)
        Unit
    }
}
