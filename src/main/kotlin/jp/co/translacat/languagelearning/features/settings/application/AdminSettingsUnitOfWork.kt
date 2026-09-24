package jp.co.translacat.languagelearning.features.settings.application

import jp.co.translacat.languagelearning.features.settings.domain.repository.AdminSettingsRepository
import java.time.LocalDateTime

internal interface AdminSettingsTransaction {
    val settings: AdminSettingsRepository
    val nowUtc: LocalDateTime
}

internal interface AdminSettingsUnitOfWork {
    suspend fun <T> execute(block: AdminSettingsTransaction.() -> T): T
}
