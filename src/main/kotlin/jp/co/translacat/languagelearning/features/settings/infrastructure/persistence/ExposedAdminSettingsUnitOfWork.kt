package jp.co.translacat.languagelearning.features.settings.infrastructure.persistence

import jp.co.translacat.languagelearning.features.settings.application.AdminSettingsTransaction
import jp.co.translacat.languagelearning.features.settings.application.AdminSettingsUnitOfWork
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.repository.ExposedAdminSettingsRepository
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal class ExposedAdminSettingsUnitOfWork(
    private val transactions: JdbcTransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
) : AdminSettingsUnitOfWork {
    override suspend fun <T> execute(block: AdminSettingsTransaction.() -> T): T = transactions.write {
        val transaction = TransactionManager.current()
        val ownerThread = Thread.currentThread()
        var active = true
        val requireTransaction = {
            check(active && Thread.currentThread() === ownerThread && TransactionManager.currentOrNull() === transaction) {
                "Repository는 생성된 트랜잭션 내부에서만 사용할 수 있습니다."
            }
        }
        val scope = object : AdminSettingsTransaction {
            override val settings = ExposedAdminSettingsRepository(requireTransaction)
            override val nowUtc: LocalDateTime
                get() = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
        }
        try {
            block(scope)
        } finally {
            active = false
        }
    }
}
