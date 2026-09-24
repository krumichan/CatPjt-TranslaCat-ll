package jp.co.translacat.languagelearning.features.settings.application

import jp.co.translacat.languagelearning.features.settings.domain.model.AdminSettings

internal class GetAdminSettings(private val unitOfWork: AdminSettingsUnitOfWork) {
    suspend fun execute(): AdminSettings = unitOfWork.execute { settings.loadForUpdate() }
}
