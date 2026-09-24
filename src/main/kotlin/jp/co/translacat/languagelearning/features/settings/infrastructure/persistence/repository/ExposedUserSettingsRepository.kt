package jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.repository

import jp.co.translacat.languagelearning.features.settings.domain.model.NewUserSettings
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettings
import jp.co.translacat.languagelearning.features.settings.domain.repository.UserSettingsRepository
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.table.UserSettingsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

internal class ExposedUserSettingsRepository(private val requireTransaction: () -> Unit) : UserSettingsRepository {
    override fun findForUser(userId: Long): UserSettings? {
        requireTransaction()
        // learner 잠금을 먼저 잡은 뒤 호출한다. RR에서도 기다린 상대의 최신 commit을 읽는다.
        return UserSettingsTable.selectAll()
            .where { UserSettingsTable.userId eq userId }
            .forUpdate()
            .singleOrNull()
            ?.let(::toModel)
    }

    override fun create(settings: NewUserSettings): UserSettings {
        requireTransaction()
        UserSettingsTable.insert {
            it[userId] = settings.userId
            it[originLanguage] = null
            it[learningLanguage] = null
            it[timezone] = settings.timezone
            it[dailySentenceCount] = settings.dailySentenceCount
            it[dailySpeakingGoalMinutes] = settings.dailySpeakingGoalMinutes
            it[dailyListeningGoalCount] = settings.dailyListeningGoalCount
            it[defaultListeningTaskTypes] = settings.defaultListeningTaskTypesJson
            it[speakingVoiceId] = settings.speakingVoiceId
            it[speakingPlaybackSpeed] = settings.speakingPlaybackSpeed
            it[pendingOriginLanguage] = null
            it[pendingLearningLanguage] = null
            it[pendingTimezone] = null
            it[pendingDailySentenceCount] = null
            it[pendingDailySpeakingGoalMinutes] = null
            it[pendingDailyListeningGoalCount] = null
            it[pendingEffectiveDate] = null
            it[createdBy] = settings.userId.toString()
            it[createdAt] = settings.nowUtc
            it[updatedBy] = settings.userId.toString()
            it[updatedAt] = settings.nowUtc
        }
        // 자동 생성 ID와 DB 시간 정밀도까지 실제 저장된 값을 재조회해 반환한다.
        return checkNotNull(findForUser(settings.userId)) { "생성한 개인 설정을 찾을 수 없습니다." }
    }

    private fun toModel(row: ResultRow) = UserSettings(
        id = row[UserSettingsTable.id],
        userId = row[UserSettingsTable.userId],
        originLanguage = row[UserSettingsTable.originLanguage],
        learningLanguage = row[UserSettingsTable.learningLanguage],
        timezone = row[UserSettingsTable.timezone],
        dailySentenceCount = row[UserSettingsTable.dailySentenceCount],
        dailySpeakingGoalMinutes = row[UserSettingsTable.dailySpeakingGoalMinutes],
        dailyListeningGoalCount = row[UserSettingsTable.dailyListeningGoalCount],
        defaultListeningTaskTypesJson = row[UserSettingsTable.defaultListeningTaskTypes],
        speakingVoiceId = row[UserSettingsTable.speakingVoiceId],
        speakingPlaybackSpeed = row[UserSettingsTable.speakingPlaybackSpeed],
        pendingOriginLanguage = row[UserSettingsTable.pendingOriginLanguage],
        pendingLearningLanguage = row[UserSettingsTable.pendingLearningLanguage],
        pendingTimezone = row[UserSettingsTable.pendingTimezone],
        pendingDailySentenceCount = row[UserSettingsTable.pendingDailySentenceCount],
        pendingDailySpeakingGoalMinutes = row[UserSettingsTable.pendingDailySpeakingGoalMinutes],
        pendingDailyListeningGoalCount = row[UserSettingsTable.pendingDailyListeningGoalCount],
        pendingEffectiveDate = row[UserSettingsTable.pendingEffectiveDate],
        createdBy = row[UserSettingsTable.createdBy],
        createdAt = row[UserSettingsTable.createdAt],
        updatedBy = row[UserSettingsTable.updatedBy],
        updatedAt = row[UserSettingsTable.updatedAt],
    )
}
