package jp.co.translacat.languagelearning.support

import jp.co.translacat.languagelearning.features.settings.application.SelectionSettingsTransaction
import jp.co.translacat.languagelearning.features.settings.application.SelectionSettingsUnitOfWork
import jp.co.translacat.languagelearning.features.settings.application.SettingsTransaction
import jp.co.translacat.languagelearning.features.settings.domain.model.SettingsSelectionDelivery
import jp.co.translacat.languagelearning.features.settings.domain.repository.SettingsSelectionDeliveryRepository

/** 동작/롤백 계약용 fake다. SQL 잠금은 별도 MySQL 테스트에서 확인한다. */
internal class MemorySelectionSettingsUnitOfWork(val settings: MemorySettingsUnitOfWork = MemorySettingsUnitOfWork()) :
    SelectionSettingsUnitOfWork {
    val deliveries = mutableMapOf<Long, SettingsSelectionDelivery>()
    var failDelivery = false
    override suspend fun <T> execute(block: SelectionSettingsTransaction.() -> T): T {
        val before = deliveries.toMap()
        try {
            return settings.execute {
                val owner = this
                block(
                    object : SelectionSettingsTransaction, SettingsTransaction by owner {
                        override val deliveries = object : SettingsSelectionDeliveryRepository {
                            override fun findForUser(userId: Long) =
                                this@MemorySelectionSettingsUnitOfWork.deliveries[userId]

                            override fun save(delivery: SettingsSelectionDelivery) {
                                if (failDelivery) error("테스트 수신 이력 저장 실패")
                                this@MemorySelectionSettingsUnitOfWork.deliveries[delivery.userId] = delivery
                            }
                        }
                    },
                )
            }
        } catch (failure: Throwable) {
            deliveries.clear(); deliveries.putAll(before); throw failure
        }
    }
}
