package jp.co.translacat.languagelearning.features.growth.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.application.GrowthTransaction
import jp.co.translacat.languagelearning.features.growth.application.GrowthUnitOfWork
import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.repository.ExposedGrowthRepository
import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.repository.ExposedLearnerRepository
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal class ExposedGrowthUnitOfWork(
    private val transactions: JdbcTransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
) : GrowthUnitOfWork {
    override suspend fun <T> write(userId: Long, block: GrowthTransaction.() -> T): T = transactions.write {
        scope { guard ->
            ExposedLearnerRepository(guard).ensureAndLock(userId, now()).requireActive()
            block()
        }
    }

    override suspend fun <T> read(block: GrowthTransaction.() -> T): T = transactions.read { scope { _ -> block() } }

    private fun now() = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)

    private fun <T> scope(block: GrowthTransaction.((() -> Unit)) -> T): T {
        val owner = Thread.currentThread()
        val tx = TransactionManager.current()
        var active = true
        val guard = {
            check(active && Thread.currentThread() === owner && TransactionManager.currentOrNull() === tx) {
                "성장 Repository는 소유 트랜잭션 안에서만 사용할 수 있습니다."
            }
        }
        val scope = object : GrowthTransaction {
            override val records = ExposedGrowthRepository(guard)
            override fun requireActiveIfPresent(userId: Long) {
                guard()
                val table =
                    jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.table.LearnersTable
                val row = table.selectAll().where { table.userId eq userId }.singleOrNull()
                if (row != null && row[table.status] != "ACTIVE")
                    throw jp.co.translacat.languagelearning.features.learner.domain.exception.LearnerUnavailableException(
                        userId,
                    )
            }
        }
        return try {
            block(scope, guard)
        } finally {
            active = false
        }
    }
}
