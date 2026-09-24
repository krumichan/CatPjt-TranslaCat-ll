package jp.co.translacat.languagelearning.features.settings.api

import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskType
import jp.co.translacat.languagelearning.features.settings.api.dto.AdminSettingResponseDto
import jp.co.translacat.languagelearning.features.settings.api.dto.AdminSettingUpdateRequestDto
import jp.co.translacat.languagelearning.features.settings.api.dto.UserSettingResponseDto
import jp.co.translacat.languagelearning.features.settings.api.dto.UserSettingUpdateRequestDto
import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsResult
import jp.co.translacat.languagelearning.features.settings.domain.model.AdminSettings
import jp.co.translacat.languagelearning.features.settings.domain.model.AdminSettingsChange
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettingsChange
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import kotlinx.serialization.json.Json

internal fun UserSettingUpdateRequestDto.toChange() = UserSettingsChange(
    originLanguage = originLanguage,
    learningLanguage = learningLanguage,
    timezone = timezone,
    dailySentenceCount = dailySentenceCount,
    dailySpeakingGoalMinutes = dailySpeakingGoalMinutes,
    speakingVoiceId = speakingVoiceId,
    speakingPlaybackSpeed = speakingPlaybackSpeed,
    dailyListeningGoalCount = dailyListeningGoalCount,
    defaultListeningTaskTypes = defaultListeningTaskTypes?.map { name ->
        if (name == null) null else try {
            ListeningTaskType.valueOf(name)
        } catch (_: IllegalArgumentException) {
            throw LearningBusinessException("INVALID_REQUEST", "Listening Task 값이 유효하지 않습니다.")
        }
    },
)

internal fun UserSettingsResult.toResponse(): UserSettingResponseDto {
    val s = settings
    // 저장 JSON이 손상되면 실패한다. 기본 DICTATION으로 덮어써서 문제를 숨기지 않는다.
    val tasks = Json.decodeFromString<List<String>>(s.defaultListeningTaskTypesJson)
    tasks.forEach { ListeningTaskType.valueOf(it) }
    return UserSettingResponseDto(
        originLanguage = s.originLanguage,
        learningLanguage = s.learningLanguage,
        timezone = s.timezone,
        dailySentenceCount = s.dailySentenceCount,
        dailySpeakingGoalMinutes = s.dailySpeakingGoalMinutes,
        speakingVoiceId = s.speakingVoiceId,
        speakingPlaybackSpeed = s.speakingPlaybackSpeed,
        dailyListeningGoalCount = s.dailyListeningGoalCount,
        defaultListeningTaskTypes = tasks,
        pendingOriginLanguage = s.pendingOriginLanguage,
        pendingLearningLanguage = s.pendingLearningLanguage,
        pendingTimezone = s.pendingTimezone,
        pendingDailySentenceCount = s.pendingDailySentenceCount,
        pendingDailySpeakingGoalMinutes = s.pendingDailySpeakingGoalMinutes,
        pendingDailyListeningGoalCount = s.pendingDailyListeningGoalCount,
        pendingEffectiveDate = s.pendingEffectiveDate?.toString(),
        minDailySentenceCount = policy.writing.minimum,
        maxDailySentenceCount = policy.writing.maximum,
        minDailySpeakingGoalMinutes = policy.speaking.minimum,
        maxDailySpeakingGoalMinutes = policy.speaking.maximum,
        minDailyListeningGoalCount = policy.listening.minimum,
        maxDailyListeningGoalCount = policy.listening.maximum,
        configured = s.configured,
    )
}

internal fun AdminSettingUpdateRequestDto.toChange() = AdminSettingsChange(
    defaultDailySentenceCount = defaultDailySentenceCount,
    minDailySentenceCount = minDailySentenceCount,
    maxDailySentenceCount = maxDailySentenceCount,
    dailyKeywordMaxCount = dailyKeywordMaxCount,
    reviewAvailableDays = reviewAvailableDays,
    levelRecheckRecommendationDays = levelRecheckRecommendationDays,
    adaptiveWritingEnabled = adaptiveWritingEnabled,
    aiEvaluationEnabled = aiEvaluationEnabled,
    speakingEnabled = speakingEnabled,
    speakingEvaluationEnabled = speakingEvaluationEnabled,
    defaultDailySpeakingGoalMinutes = defaultDailySpeakingGoalMinutes,
    minDailySpeakingGoalMinutes = minDailySpeakingGoalMinutes,
    maxDailySpeakingGoalMinutes = maxDailySpeakingGoalMinutes,
    dailySpeakingHardLimitMinutes = dailySpeakingHardLimitMinutes,
    dailySpeakingSessionLimit = dailySpeakingSessionLimit,
    maxSessionMinutes = maxSessionMinutes,
    maxTurnsPerSession = maxTurnsPerSession,
    minValidAudioSeconds = minValidAudioSeconds,
    maxTurnAudioSeconds = maxTurnAudioSeconds,
    maxAudioFileBytes = maxAudioFileBytes,
    rawAudioRetentionDays = rawAudioRetentionDays,
    reportedAudioRetentionDays = reportedAudioRetentionDays,
    activeSessionResumeHours = activeSessionResumeHours,
    automaticRetryLimitPerStage = automaticRetryLimitPerStage,
    manualRetryLimitPerStage = manualRetryLimitPerStage,
    sttTimeoutSeconds = sttTimeoutSeconds,
    ttsTimeoutSeconds = ttsTimeoutSeconds,
    evaluationTimeoutSeconds = evaluationTimeoutSeconds,
    levelTestQuestionPoolTargetSize = levelTestQuestionPoolTargetSize,
    levelTestQuestionPoolReplenishmentEnabled = levelTestQuestionPoolReplenishmentEnabled,
)

internal fun AdminSettings.toResponse() = AdminSettingResponseDto(
    defaultDailySentenceCount = defaultDailySentenceCount,
    minDailySentenceCount = minDailySentenceCount,
    maxDailySentenceCount = maxDailySentenceCount,
    dailyKeywordMaxCount = dailyKeywordMaxCount,
    reviewAvailableDays = reviewAvailableDays,
    levelRecheckRecommendationDays = levelRecheckRecommendationDays,
    adaptiveWritingEnabled = adaptiveWritingEnabled,
    aiEvaluationEnabled = aiEvaluationEnabled,
    speakingEnabled = speakingEnabled,
    speakingEvaluationEnabled = speakingEvaluationEnabled,
    defaultDailySpeakingGoalMinutes = defaultDailySpeakingGoalMinutes,
    minDailySpeakingGoalMinutes = minDailySpeakingGoalMinutes,
    maxDailySpeakingGoalMinutes = maxDailySpeakingGoalMinutes,
    dailySpeakingHardLimitMinutes = dailySpeakingHardLimitMinutes,
    dailySpeakingSessionLimit = dailySpeakingSessionLimit,
    maxSessionMinutes = maxSessionMinutes,
    maxTurnsPerSession = maxTurnsPerSession,
    minValidAudioSeconds = minValidAudioSeconds,
    maxTurnAudioSeconds = maxTurnAudioSeconds,
    maxAudioFileBytes = maxAudioFileBytes,
    rawAudioRetentionDays = rawAudioRetentionDays,
    reportedAudioRetentionDays = reportedAudioRetentionDays,
    activeSessionResumeHours = activeSessionResumeHours,
    automaticRetryLimitPerStage = automaticRetryLimitPerStage,
    manualRetryLimitPerStage = manualRetryLimitPerStage,
    sttTimeoutSeconds = sttTimeoutSeconds,
    ttsTimeoutSeconds = ttsTimeoutSeconds,
    evaluationTimeoutSeconds = evaluationTimeoutSeconds,
    levelTestQuestionPoolTargetSize = resolvedQuestionPoolTarget(),
    levelTestQuestionPoolReplenishmentEnabled = resolvedQuestionPoolReplenishment(),
)
