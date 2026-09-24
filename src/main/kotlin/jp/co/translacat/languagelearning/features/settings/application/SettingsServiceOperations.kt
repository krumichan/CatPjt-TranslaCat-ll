package jp.co.translacat.languagelearning.features.settings.application

import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningPolicy
import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsSnapshot
import jp.co.translacat.languagelearning.features.settings.domain.model.AdminSettings
import jp.co.translacat.languagelearning.features.settings.domain.model.ConfiguredLanguagePair
import java.time.LocalDate

/** 남아 있는 BE 학습 기능/작업자를 위한 서비스 전용 조회 포트. 임의의 관리자 사칭은 하지 않는다. */
internal interface SettingsServiceOperations {
    suspend fun userSnapshot(userId: Long): UserSettingsSnapshot
    suspend fun learningDate(userId: Long): LocalDate
    suspend fun adminPolicy(): AdminSettings
    suspend fun listeningPolicy(): ListeningPolicy
    suspend fun configuredLanguagePairs(): List<ConfiguredLanguagePair>
}
