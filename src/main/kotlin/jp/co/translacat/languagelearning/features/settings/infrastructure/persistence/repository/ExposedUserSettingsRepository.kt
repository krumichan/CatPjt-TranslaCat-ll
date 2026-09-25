package jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.repository

import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskType
import jp.co.translacat.languagelearning.features.settings.domain.model.NewUserSettings
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettings
import jp.co.translacat.languagelearning.features.settings.domain.repository.UserSettingsRepository
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.table.UserSettingsTable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

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

    override fun save(settings: UserSettings): UserSettings {
        requireTransaction()
        val count = UserSettingsTable.update(
            {
                (UserSettingsTable.id eq settings.id) and (UserSettingsTable.userId eq settings.userId)
            },
        ) {
            it[originLanguage] = settings.originLanguage
            it[learningLanguage] = settings.learningLanguage
            it[timezone] = settings.timezone
            it[dailySentenceCount] = settings.dailySentenceCount
            it[dailySpeakingGoalMinutes] = settings.dailySpeakingGoalMinutes
            it[dailyListeningGoalCount] = settings.dailyListeningGoalCount
            it[speakingVoiceId] = settings.speakingVoiceId
            it[speakingPlaybackSpeed] = settings.speakingPlaybackSpeed
            it[pendingOriginLanguage] = settings.pendingOriginLanguage
            it[pendingLearningLanguage] = settings.pendingLearningLanguage
            it[pendingTimezone] = settings.pendingTimezone
            it[pendingDailySentenceCount] = settings.pendingDailySentenceCount
            it[pendingDailySpeakingGoalMinutes] = settings.pendingDailySpeakingGoalMinutes
            it[pendingDailyListeningGoalCount] = settings.pendingDailyListeningGoalCount
            it[pendingEffectiveDate] = settings.pendingEffectiveDate
            it[updatedBy] = settings.updatedBy
            it[updatedAt] = settings.updatedAt
            it[defaultListeningTaskTypes] = settings.defaultListeningTaskTypesJson
        }
        // useAffectedRows=true이면 같은 값 UPDATE가 0을 반환할 수 있으므로 재조회로 존재/내용을 검증한다.
        check(count in 0..1) { "개인 설정 갱신 행 수가 올바르지 않습니다." }
        val stored = checkNotNull(findForUser(settings.userId)) { "갱신할 개인 설정이 없습니다." }
        check(stored == settings) { "개인 설정 저장 결과가 요청한 상태와 다릅니다." }
        return stored
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
    ).also { setting ->
        // 손상된 저장 JSON은 조회 트랜잭션 안에서 실패시킨다. 승격만 커밋한 뒤 응답이 실패하지 않게 한다.
        Json.decodeFromString<List<String>>(setting.defaultListeningTaskTypesJson)
            .forEach { ListeningTaskType.valueOf(it) }
    }
}
