package jp.co.translacat.languagelearning.support

import jp.co.translacat.languagelearning.features.settings.domain.model.AdminSettings
import jp.co.translacat.languagelearning.features.settings.domain.model.GoalPolicy
import jp.co.translacat.languagelearning.features.settings.domain.model.InitialSettingsPolicy
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettings
import java.time.LocalDateTime

internal object SettingsFixtures {
    val now: LocalDateTime = LocalDateTime.parse("2026-09-24T03:30:00.123456")
    fun policy() = InitialSettingsPolicy(GoalPolicy(5, 1, 20), GoalPolicy(5, 3, 20), GoalPolicy(5, 1, 20))
    fun user(userId: Long = 123) = UserSettings(
        id = userId, userId = userId, originLanguage = null, learningLanguage = null,
        timezone = "Asia/Tokyo", dailySentenceCount = 5, dailySpeakingGoalMinutes = 5, dailyListeningGoalCount = 5,
        defaultListeningTaskTypesJson = "[\"DICTATION\"]", speakingVoiceId = "marin", speakingPlaybackSpeed = "NORMAL",
        pendingOriginLanguage = null, pendingLearningLanguage = null, pendingTimezone = null,
        pendingDailySentenceCount = null, pendingDailySpeakingGoalMinutes = null, pendingDailyListeningGoalCount = null,
        pendingEffectiveDate = null, createdBy = userId.toString(), createdAt = now,
        updatedBy = userId.toString(), updatedAt = now,
    )
    fun configured(userId: Long = 123) = user(userId).copy(originLanguage = "ko", learningLanguage = "ja")
    fun admin() = AdminSettings(
        defaultDailySentenceCount = 5,
        minDailySentenceCount = 1,
        maxDailySentenceCount = 20,
        dailyKeywordMaxCount = 5,
        reviewAvailableDays = 7,
        levelRecheckRecommendationDays = 30,
        adaptiveWritingEnabled = true,
        aiEvaluationEnabled = true,
        speakingEnabled = true,
        speakingEvaluationEnabled = true,
        defaultDailySpeakingGoalMinutes = 5,
        minDailySpeakingGoalMinutes = 3,
        maxDailySpeakingGoalMinutes = 20,
        dailySpeakingHardLimitMinutes = 30,
        dailySpeakingSessionLimit = 5,
        maxSessionMinutes = 10,
        maxTurnsPerSession = 20,
        minValidAudioSeconds = 1.0,
        maxTurnAudioSeconds = 60,
        maxAudioFileBytes = 10485760L,
        rawAudioRetentionDays = 7,
        reportedAudioRetentionDays = 30,
        activeSessionResumeHours = 2,
        automaticRetryLimitPerStage = 2,
        manualRetryLimitPerStage = 1,
        sttTimeoutSeconds = 30,
        ttsTimeoutSeconds = 30,
        evaluationTimeoutSeconds = 60,
        levelTestQuestionPoolTargetSize = 1000,
        levelTestQuestionPoolReplenishmentEnabled = false,
    )
}
