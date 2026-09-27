package jp.co.translacat.languagelearning.features.speaking.infrastructure

import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.repository.ExposedGrowthRepository
import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.repository.ExposedLearnerRepository
import jp.co.translacat.languagelearning.features.speaking.application.SpeakingTransaction
import jp.co.translacat.languagelearning.features.speaking.application.SpeakingUnitOfWork
import jp.co.translacat.languagelearning.features.speaking.infrastructure.persistence.repository.ExposedSpeakingRepository
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal class ExposedSpeakingUnitOfWork(
    private val transactions: JdbcTransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
) : SpeakingUnitOfWork {
    override suspend fun <T> read(block: SpeakingTransaction.() -> T): T = transactions.read { scoped(block) }
    override suspend fun <T> catalogWrite(block: SpeakingTransaction.() -> T): T = transactions.write { scoped(block) }
    override suspend fun <T> write(userId: Long, block: SpeakingTransaction.() -> T): T = transactions.write {
        // 세션 생성·턴 교체·평가 완료와 Growth 기록의 쓰기 순서를 동일 학습자 잠금으로 고정한다.
        scoped {
            ExposedLearnerRepository {}.ensureAndLock(userId, nowUtc).requireActive()
            block()
        }
    }

    private fun <T> scoped(block: SpeakingTransaction.() -> T): T {
        val owner = Thread.currentThread()
        val transaction = TransactionManager.current()
        var active = true
        val guard = {
            check(active && Thread.currentThread() === owner && TransactionManager.currentOrNull() === transaction) {
                "Speaking Repository는 소유 트랜잭션 안에서만 사용할 수 있습니다."
            }
        }
        val scope = object : SpeakingTransaction {
            override val records = ExposedSpeakingRepository(guard)
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
