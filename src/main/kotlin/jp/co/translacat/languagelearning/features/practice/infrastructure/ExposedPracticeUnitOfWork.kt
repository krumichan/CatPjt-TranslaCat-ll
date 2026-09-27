package jp.co.translacat.languagelearning.features.practice.infrastructure

import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.repository.ExposedGrowthRepository
import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.repository.ExposedLearnerRepository
import jp.co.translacat.languagelearning.features.practice.application.PracticeTransaction
import jp.co.translacat.languagelearning.features.practice.application.PracticeUnitOfWork
import jp.co.translacat.languagelearning.features.practice.infrastructure.persistence.repository.ExposedPracticeRepository
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal class ExposedPracticeUnitOfWork(
    private val transactions: JdbcTransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
) : PracticeUnitOfWork {
    override suspend fun <T> read(block: PracticeTransaction.() -> T): T = transactions.read { scoped(block) }

    override suspend fun <T> write(userId: Long, block: PracticeTransaction.() -> T): T = transactions.write {
        // 같은 학습자의 생성과 답변/Growth 갱신은 동일 잠금 안에서 처리한다.
        scoped {
            ExposedLearnerRepository {}.ensureAndLock(userId, nowUtc).requireActive()
            block()
        }
    }

    private fun <T> scoped(block: PracticeTransaction.() -> T): T {
        val owner = Thread.currentThread()
        val transaction = TransactionManager.current()
        var active = true
        val guard = {
            check(active && Thread.currentThread() === owner && TransactionManager.currentOrNull() === transaction) {
                "Practice Repository는 소유 트랜잭션 안에서만 사용할 수 있습니다."
            }
        }
        val scope = object : PracticeTransaction {
            override val records = ExposedPracticeRepository(guard)
            override val growth = ExposedGrowthRepository(guard)
            override val nowUtc =
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
        }
        return try {
            block(scope)
        } finally {
            active = false
        }
    }
}
