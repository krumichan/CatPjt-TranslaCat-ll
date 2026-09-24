package jp.co.translacat.languagelearning.features.settings.domain.repository

import jp.co.translacat.languagelearning.features.settings.domain.model.NewUserSettings
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettings

internal interface UserSettingsRepository {
    fun findForUser(userId: Long): UserSettings?
    fun create(settings: NewUserSettings): UserSettings
}
