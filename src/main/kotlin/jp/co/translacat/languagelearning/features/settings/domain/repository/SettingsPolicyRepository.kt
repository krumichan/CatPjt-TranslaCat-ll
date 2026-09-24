package jp.co.translacat.languagelearning.features.settings.domain.repository

import jp.co.translacat.languagelearning.features.settings.domain.model.InitialSettingsPolicy

internal interface SettingsPolicyRepository {
    fun loadInitialPolicy(): InitialSettingsPolicy
}
