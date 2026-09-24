package jp.co.translacat.languagelearning.features.settings.application

import jp.co.translacat.languagelearning.features.settings.domain.model.AdminSettingsChange
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettingsChange

internal class DefaultSettingsOperations(
    private val getUserSettings: GetUserSettings,
    private val updateUserSettings: UpdateUserSettings,
    private val getAdminSettings: GetAdminSettings,
    private val updateAdminSettings: UpdateAdminSettings,
) : SettingsOperations {
    override suspend fun getUser(userId: Long) = getUserSettings.execute(userId)
    override suspend fun updateUser(userId: Long, change: UserSettingsChange) =
        updateUserSettings.execute(userId, change)

    override suspend fun getAdmin() = getAdminSettings.execute()
    override suspend fun updateAdmin(adminUserId: Long, change: AdminSettingsChange) =
        updateAdminSettings.execute(adminUserId, change)
}
