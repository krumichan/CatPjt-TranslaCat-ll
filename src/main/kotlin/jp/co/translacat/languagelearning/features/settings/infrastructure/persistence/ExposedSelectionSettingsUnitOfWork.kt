package jp.co.translacat.languagelearning.features.settings.infrastructure.persistence

import jp.co.translacat.languagelearning.features.settings.application.SelectionSettingsTransaction
import jp.co.translacat.languagelearning.features.settings.application.SelectionSettingsUnitOfWork
import jp.co.translacat.languagelearning.features.settings.application.SettingsTransaction
import jp.co.translacat.languagelearning.features.settings.application.SettingsUnitOfWork
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.repository.ExposedSettingsSelectionDeliveryRepository
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/** 기존 Settings 트랜잭션을 재사용한다. 새로운 DB 트랜잭션을 내부에서 열지 않는다. */
internal class ExposedSelectionSettingsUnitOfWork(private val settings: SettingsUnitOfWork) : SelectionSettingsUnitOfWork {
    override suspend fun <T> execute(block: SelectionSettingsTransaction.() -> T): T = settings.execute {
        val owner = this
        val transaction = TransactionManager.current()
        val thread = Thread.currentThread()
        var active = true
        val scope = object : SelectionSettingsTransaction, SettingsTransaction by owner {
            override val deliveries = ExposedSettingsSelectionDeliveryRepository {
                check(active && Thread.currentThread() === thread && TransactionManager.currentOrNull() === transaction) {
                    "수신 이력 저장소는 생성한 트랜잭션 안에서만 사용할 수 있습니다."
                }
            }
        }
        try { block(scope) } finally { active = false }
    }
}
