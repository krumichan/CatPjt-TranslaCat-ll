package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.repository.ExposedGrowthRepository
import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.repository.ExposedLearnerRepository
import jp.co.translacat.languagelearning.features.writing.application.WritingSetTransaction
import jp.co.translacat.languagelearning.features.writing.application.WritingSetUnitOfWork
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.repository.*
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal class ExposedWritingSetUnitOfWork(
    private val transactions: JdbcTransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
) : WritingSetUnitOfWork {
    override suspend fun <T> write(userId: Long, block: WritingSetTransaction.() -> T): T = transactions.write {
        scoped { guard ->
            ExposedLearnerRepository(guard).ensureAndLock(userId, now()).requireActive()
            block()
        }
    }

    override suspend fun <T> read(block: WritingSetTransaction.() -> T): T = transactions.read {
        scoped { _ -> block() }
    }

    private fun now() = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)

    private fun <T> scoped(block: WritingSetTransaction.((() -> Unit)) -> T): T {
        val owner = Thread.currentThread()
        val transaction = TransactionManager.current()
        var active = true
        val guard = {
            check(active && Thread.currentThread() === owner && TransactionManager.currentOrNull() === transaction) {
                "Writing Repository는 소유 트랜잭션 안에서만 사용할 수 있습니다."
            }
        }
        val scope = object : WritingSetTransaction {
            override val sets = ExposedWritingSetRepository(guard)
            override val items = ExposedWritingItemRepository(guard)
            override val answers = ExposedWritingAnswerRepository(guard)
            override val evaluations = ExposedWritingEvaluationRepository(guard)
            override val fingerprints = ExposedWritingFingerprintRepository(guard)
            override val growth = ExposedGrowthRepository(guard)
            override val nowUtc: LocalDateTime get() = now()
        }
        try {
            return block(scope, guard)
        } finally {
            active = false
        }
    }
}
