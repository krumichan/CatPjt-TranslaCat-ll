package jp.co.translacat.languagelearning.features.settings.application

import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsResult
import jp.co.translacat.languagelearning.features.settings.domain.model.AdminSettings
import jp.co.translacat.languagelearning.features.settings.domain.model.AdminSettingsChange
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettingsChange

/** HTTP 어댑터의 입력 포트다. 기술 의존 없이 업무 유스케이스로 위임한다. */
internal interface SettingsOperations {
    suspend fun getUser(userId: Long): UserSettingsResult
    suspend fun updateUser(userId: Long, change: UserSettingsChange): UserSettingsResult
    suspend fun getAdmin(): AdminSettings
    suspend fun updateAdmin(adminUserId: Long, change: AdminSettingsChange): AdminSettings
}
