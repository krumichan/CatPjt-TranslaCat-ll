package jp.co.translacat.languagelearning.features.settings.application

import jp.co.translacat.languagelearning.features.settings.domain.model.NewUserSettings
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettings

/**
 * 내부 서비스용 최초 생성/조회 기능이다. 이 메서드 자체가 사용자를 인증하지는 않는다.
 * HTTP 어댑터는 추후 구현하며, 연결 시 반드시 검증된 사용자 식별자만 전달해야 한다.
 * 기존 BE의 pending 승격·범위 보정·설정 변경 정책은 이 기능의 범위가 아니다.
 */
/** 초기 저장 기반의 원시 조회 계약이다. HTTP 조회·변경은 승격/보정이 포함된 GetUserSettings를 사용한다. */
internal class GetOrCreateUserSettings(private val unitOfWork: SettingsUnitOfWork) {
    suspend fun execute(userId: Long): UserSettings {
        require(userId > 0) { "userId는 양수여야 합니다." }
        return unitOfWork.execute {
            // 첫 SQL부터 learner 생성 경쟁을 정리하고, 이 사용자에 대한 배타 잠금을 유지한다.
            learners.ensureAndLock(userId, nowUtc).requireActive()
            userSettings.findForUser(userId) ?: run {
                val initial = NewUserSettings.fromPolicy(userId, policies.loadInitialPolicy(), nowUtc)
                userSettings.create(initial)
            }
        }
    }
}
