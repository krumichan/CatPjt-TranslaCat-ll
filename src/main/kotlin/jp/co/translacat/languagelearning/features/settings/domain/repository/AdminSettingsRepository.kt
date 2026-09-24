package jp.co.translacat.languagelearning.features.settings.domain.repository

import jp.co.translacat.languagelearning.features.settings.domain.model.AdminSettings
import java.time.LocalDateTime

internal interface AdminSettingsRepository {
    fun loadForUpdate(): AdminSettings
    fun save(settings: AdminSettings, adminUserId: Long, nowUtc: LocalDateTime): AdminSettings
    fun appendAudit(adminUserId: Long, before: AdminSettings, after: AdminSettings, nowUtc: LocalDateTime)
}
