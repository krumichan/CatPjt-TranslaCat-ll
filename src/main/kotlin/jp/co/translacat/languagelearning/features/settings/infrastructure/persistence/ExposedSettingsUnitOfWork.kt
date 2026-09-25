package jp.co.translacat.languagelearning.features.settings.infrastructure.persistence

import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.repository.ExposedLearnerRepository
import jp.co.translacat.languagelearning.features.settings.application.SettingsTransaction
import jp.co.translacat.languagelearning.features.settings.application.SettingsUnitOfWork
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.repository.ExposedSettingsPolicyRepository
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.repository.ExposedUserSettingsRepository
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal class ExposedSettingsUnitOfWork(
    private val transactions: JdbcTransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
) : SettingsUnitOfWork {
    override suspend fun <T> execute(block: SettingsTransaction.() -> T): T = transactions.write {
        val transaction = TransactionManager.current()
        val ownerThread = Thread.currentThread()
        var active = true
        val requireTransaction = {
            check(
                active && Thread.currentThread() === ownerThread && TransactionManager.currentOrNull() === transaction,
            ) {
                "Repository는 생성된 트랜잭션 내부에서만 사용할 수 있습니다."
            }
        }
        val scope = object : SettingsTransaction {
            override val learners = ExposedLearnerRepository(requireTransaction)
            override val userSettings = ExposedUserSettingsRepository(requireTransaction)
            override val policies = ExposedSettingsPolicyRepository(requireTransaction)

            // 잠금 대기 후에도 실제 시각을 읽는다. 학습 날짜는 별도로 사용자 timezone에서 계산한다.
            override val nowUtc: LocalDateTime
                get() = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
        }
        try {
            block(scope)
        } finally {
            // 트랜잭션/Repository가 호출자에게 유출되어 뒤늦게 재사용되는 것도 차단한다.
            active = false
        }
    }
}
