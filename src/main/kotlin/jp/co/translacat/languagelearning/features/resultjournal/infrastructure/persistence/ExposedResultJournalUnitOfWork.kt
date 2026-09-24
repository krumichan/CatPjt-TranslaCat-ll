package jp.co.translacat.languagelearning.features.resultjournal.infrastructure.persistence

import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.repository.ExposedLearnerRepository
import jp.co.translacat.languagelearning.features.resultjournal.application.ResultJournalUnitOfWork
import jp.co.translacat.languagelearning.features.resultjournal.domain.repository.ResultJournalRepository
import jp.co.translacat.languagelearning.features.resultjournal.infrastructure.persistence.repository.ExposedResultJournalRepository
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.LocalDateTime
import java.time.ZoneOffset

internal class ExposedResultJournalUnitOfWork(private val transactions: JdbcTransactionRunner) : ResultJournalUnitOfWork {
    override suspend fun <T> execute(userId: Long, block: ResultJournalRepository.() -> T): T = transactions.write {
        val transaction = TransactionManager.current()
        val thread = Thread.currentThread()
        var active = true
        val guard = {
            check(active && Thread.currentThread() == thread && TransactionManager.currentOrNull() === transaction) {
                "결과 원장 저장소를 트랜잭션 밖에서 사용할 수 없습니다."
            }
        }
        try {
            ExposedLearnerRepository(guard).ensureAndLock(userId, LocalDateTime.now(ZoneOffset.UTC)).requireActive()
            block(ExposedResultJournalRepository(guard))
        } finally {
            active = false
        }
    }
}
