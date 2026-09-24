package jp.co.translacat.languagelearning.features.settings.application

import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsSnapshot
import jp.co.translacat.languagelearning.features.settings.domain.policy.UserSettingsPolicy
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset

internal class DefaultSettingsServiceOperations(
    private val unitOfWork: SettingsUnitOfWork,
    private val queries: SettingsReadQueries,
    private val getAdmin: GetAdminSettings,
    private val clock: Clock = Clock.systemUTC(),
) : SettingsServiceOperations {
    override suspend fun userSnapshot(userId: Long): UserSettingsSnapshot {
        require(userId > 0) { "userId는 양수여야 합니다." }
        return unitOfWork.execute {
            val result = loadCurrentUserSettings(userId)
            UserSettingsSnapshot(userId, UserSettingsPolicy.today(result.settings.timezone, nowUtc), result)
        }
    }

    override suspend fun learningDate(userId: Long): java.time.LocalDate {
        require(userId > 0) { "userId는 양수여야 합니다." }
        // 기존 resolveToday(userId)의 무생성/무승격 동작을 유지한다. 통신 실패를 기본 날짜로 숨기지 않는다.
        val timezone = queries.findTimezone(userId)
        return UserSettingsPolicy.today(timezone, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
    }

    override suspend fun adminPolicy() = getAdmin.execute()
    override suspend fun listeningPolicy() = queries.listeningPolicy()
    override suspend fun configuredLanguagePairs() = queries.configuredLanguagePairs()
}
