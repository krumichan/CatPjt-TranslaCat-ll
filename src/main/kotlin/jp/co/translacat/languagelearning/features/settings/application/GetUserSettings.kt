package jp.co.translacat.languagelearning.features.settings.application

import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsResult
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettings
import jp.co.translacat.languagelearning.features.settings.domain.policy.UserSettingsPolicy

internal class GetUserSettings(private val unitOfWork: SettingsUnitOfWork) {
    suspend fun execute(userId: Long): UserSettingsResult {
        require(userId > 0) { "userId는 양수여야 합니다." }
        return unitOfWork.execute { loadCurrentUserSettings(userId) }
    }

    /** 향후 학습 생성 진입점에서 쓰는 검사이며, 미설정 상태를 임의로 언어 선택하지 않는다. */
    suspend fun requireConfigured(userId: Long): UserSettings = execute(userId).settings.also {
        UserSettingsPolicy.requireConfigured(it)
    }
}
