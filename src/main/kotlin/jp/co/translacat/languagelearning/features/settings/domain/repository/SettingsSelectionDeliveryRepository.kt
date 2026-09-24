package jp.co.translacat.languagelearning.features.settings.domain.repository

import jp.co.translacat.languagelearning.features.settings.domain.model.SettingsSelectionDelivery

internal interface SettingsSelectionDeliveryRepository {
    fun findForUser(userId: Long): SettingsSelectionDelivery?
    fun save(delivery: SettingsSelectionDelivery)
}
