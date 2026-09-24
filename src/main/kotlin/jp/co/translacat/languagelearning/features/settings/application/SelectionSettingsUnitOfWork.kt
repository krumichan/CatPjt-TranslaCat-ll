package jp.co.translacat.languagelearning.features.settings.application

import jp.co.translacat.languagelearning.features.settings.domain.repository.SettingsSelectionDeliveryRepository

/** 설정 변경과 수신 이력 저장을 하나의 LL 트랜잭션으로 묶는다. */
internal interface SelectionSettingsTransaction : SettingsTransaction {
    val deliveries: SettingsSelectionDeliveryRepository
}

internal interface SelectionSettingsUnitOfWork {
    suspend fun <T> execute(block: SelectionSettingsTransaction.() -> T): T
}
