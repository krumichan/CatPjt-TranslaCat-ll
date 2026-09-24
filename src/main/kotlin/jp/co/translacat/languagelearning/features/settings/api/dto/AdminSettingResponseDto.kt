package jp.co.translacat.languagelearning.features.settings.api.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class AdminSettingResponseDto(
    val defaultDailySentenceCount: Int,
    val minDailySentenceCount: Int,
    val maxDailySentenceCount: Int,
    val dailyKeywordMaxCount: Int,
    val reviewAvailableDays: Int,
    val levelRecheckRecommendationDays: Int,
    val adaptiveWritingEnabled: Boolean,
    val aiEvaluationEnabled: Boolean,
    val speakingEnabled: Boolean,
    val speakingEvaluationEnabled: Boolean,
    val defaultDailySpeakingGoalMinutes: Int,
    val minDailySpeakingGoalMinutes: Int,
    val maxDailySpeakingGoalMinutes: Int,
    val dailySpeakingHardLimitMinutes: Int,
    val dailySpeakingSessionLimit: Int,
    val maxSessionMinutes: Int,
    val maxTurnsPerSession: Int,
    val minValidAudioSeconds: Double,
    val maxTurnAudioSeconds: Int,
    val maxAudioFileBytes: Long,
    val rawAudioRetentionDays: Int,
    val reportedAudioRetentionDays: Int,
    val activeSessionResumeHours: Int,
    val automaticRetryLimitPerStage: Int,
    val manualRetryLimitPerStage: Int,
    val sttTimeoutSeconds: Int,
    val ttsTimeoutSeconds: Int,
    val evaluationTimeoutSeconds: Int,
    val levelTestQuestionPoolTargetSize: Int,
    val levelTestQuestionPoolReplenishmentEnabled: Boolean,
)
