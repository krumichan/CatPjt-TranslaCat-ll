package jp.co.translacat.languagelearning.features.leveltest.infrastructure.persistence

import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.repository.ExposedLearnerRepository
import jp.co.translacat.languagelearning.features.leveltest.application.LevelTestTransaction
import jp.co.translacat.languagelearning.features.leveltest.application.LevelTestUnitOfWork
import jp.co.translacat.languagelearning.features.leveltest.infrastructure.persistence.repository.ExposedLevelTestRepository
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal class ExposedLevelTestUnitOfWork(
    private val transactions: JdbcTransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
) : LevelTestUnitOfWork {
    override suspend fun <T> write(userId: Long?, block: LevelTestTransaction.() -> T): T = transactions.write {
        scope { guard ->
            if (userId != null) ExposedLearnerRepository(guard).ensureAndLock(userId, now()).requireActive()
            block()
        }
    }

    override suspend fun <T> read(block: LevelTestTransaction.() -> T): T = transactions.read {
        scope { _ -> block() }
    }

    private fun now(): LocalDateTime =
        LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)

    private fun <T> scope(block: LevelTestTransaction.((() -> Unit)) -> T): T {
        val owner = Thread.currentThread()
        val transaction = TransactionManager.current()
        var active = true
        val guard = {
            check(active && Thread.currentThread() === owner && TransactionManager.currentOrNull() === transaction) {
                "레벨 테스트 Repository는 소유 트랜잭션 내부에서만 사용할 수 있습니다."
            }
        }
        val scope = object : LevelTestTransaction {
            override val records = ExposedLevelTestRepository(guard)
            override val nowUtc get() = now()
        }
        try {
            return block(scope, guard)
        } finally {
            active = false
        }
    }
}
