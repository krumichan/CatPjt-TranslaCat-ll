package jp.co.translacat.languagelearning.features.settings.application

import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsResult
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettingsChange
import jp.co.translacat.languagelearning.features.settings.domain.policy.UserSettingsPolicy

internal class UpdateUserSettings(private val unitOfWork: SettingsUnitOfWork) {
    suspend fun execute(userId: Long, change: UserSettingsChange): UserSettingsResult {
        require(userId > 0) { "userId는 양수여야 합니다." }
        return unitOfWork.execute {
            val current = loadCurrentUserSettings(userId)
            val changed = UserSettingsPolicy.change(current.settings, change, current.policy, nowUtc)
            // 요청 검증에 실패하면 앞서 수행한 조회 승격/보정도 같은 트랜잭션에서 롤백한다.
            UserSettingsResult(saveIfChanged(current.settings, changed), current.policy)
        }
    }
}
