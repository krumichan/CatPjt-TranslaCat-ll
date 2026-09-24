package jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.repository

import jp.co.translacat.languagelearning.features.settings.domain.model.SettingsSelectionDelivery
import jp.co.translacat.languagelearning.features.settings.domain.repository.SettingsSelectionDeliveryRepository
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.table.SettingsSelectionDeliveriesTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** caller의 learner 잠금 안에서만 조회/저장한다. */
internal class ExposedSettingsSelectionDeliveryRepository(private val requireTransaction: () -> Unit) : SettingsSelectionDeliveryRepository {
    override fun findForUser(userId: Long): SettingsSelectionDelivery? {
        requireTransaction()
        return SettingsSelectionDeliveriesTable.selectAll()
            .where { SettingsSelectionDeliveriesTable.userId eq userId }
            .forUpdate().singleOrNull()?.let {
                SettingsSelectionDelivery(it[SettingsSelectionDeliveriesTable.userId], it[SettingsSelectionDeliveriesTable.lastEventId],
                    it[SettingsSelectionDeliveriesTable.baseRevision], it[SettingsSelectionDeliveriesTable.appliedRevision])
            }
    }

    override fun save(delivery: SettingsSelectionDelivery) {
        requireTransaction()
        if (findForUser(delivery.userId) == null) {
            SettingsSelectionDeliveriesTable.insert {
                it[SettingsSelectionDeliveriesTable.userId] = delivery.userId
                it[SettingsSelectionDeliveriesTable.lastEventId] = delivery.lastEventId
                it[SettingsSelectionDeliveriesTable.baseRevision] = delivery.baseRevision
                it[SettingsSelectionDeliveriesTable.appliedRevision] = delivery.appliedRevision
            }
        } else {
            SettingsSelectionDeliveriesTable.update({ SettingsSelectionDeliveriesTable.userId eq delivery.userId }) {
                it[SettingsSelectionDeliveriesTable.lastEventId] = delivery.lastEventId
                it[SettingsSelectionDeliveriesTable.baseRevision] = delivery.baseRevision
                it[SettingsSelectionDeliveriesTable.appliedRevision] = delivery.appliedRevision
            }
        }
    }
}
