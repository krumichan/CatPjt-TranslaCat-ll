package jp.co.translacat.languagelearning.features.settings.application

import jp.co.translacat.languagelearning.features.learner.domain.repository.LearnerRepository
import jp.co.translacat.languagelearning.features.settings.domain.repository.SettingsPolicyRepository
import jp.co.translacat.languagelearning.features.settings.domain.repository.UserSettingsRepository
import java.time.LocalDateTime

/** 하나의 기능에서 사용하는 Repository들이 같은 트랜잭션을 공유한다. */
internal interface SettingsTransaction {
    val learners: LearnerRepository
    val userSettings: UserSettingsRepository
    val policies: SettingsPolicyRepository
    val nowUtc: LocalDateTime
}

internal interface SettingsUnitOfWork {
    // 내부 블록은 비-suspend다. DB 잠금을 잡은 채 AI/HTTP 호출로 중단하지 않는다.
    suspend fun <T> execute(block: SettingsTransaction.() -> T): T
}
