package jp.co.translacat.languagelearning.features.settings.application

import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsResult
import jp.co.translacat.languagelearning.features.settings.domain.model.NewUserSettings
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettings
import jp.co.translacat.languagelearning.features.settings.domain.policy.UserSettingsPolicy

/** 호출자는 이미 트랜잭션 안에 있어야 한다. 공개 진입점은 검증된 userId만 전달한다. */
internal fun SettingsTransaction.loadCurrentUserSettings(userId: Long): UserSettingsResult {
    learners.ensureAndLock(userId, nowUtc).requireActive()
    // 잠금 대기 중 자정이 지난 경우도 반영하기 위해 확보 후 시간을 다시 읽는다.
    val policy = policies.loadInitialPolicy()
    val current =
        userSettings.findForUser(userId) ?: userSettings.create(NewUserSettings.fromPolicy(userId, policy, nowUtc))
    val normalized = UserSettingsPolicy.synchronize(current, policy, nowUtc)
    return UserSettingsResult(saveIfChanged(current, normalized), policy)
}

internal fun SettingsTransaction.saveIfChanged(before: UserSettings, after: UserSettings): UserSettings {
    if (before == after) return before
    return userSettings.save(after.copy(updatedBy = after.userId.toString(), updatedAt = nowUtc))
}
