package jp.co.translacat.languagelearning.features.settings.api.dto

import kotlinx.serialization.Serializable

/** 기존 BE 응답과 ISO 날짜 형식을 유지하며 설명 언어의 활성·예약값을 함께 반환한다. */
@Serializable
internal data class UserSettingResponseDto(
    val originLanguage: String?,
    val learningLanguage: String?,
    val feedbackLanguage: String?,
    val timezone: String,
    val dailySentenceCount: Int,
    val dailySpeakingGoalMinutes: Int,
    val speakingVoiceId: String,
    val speakingPlaybackSpeed: String,
    val dailyListeningGoalCount: Int,
    val defaultListeningTaskTypes: List<String>,
    val pendingOriginLanguage: String?,
    val pendingLearningLanguage: String?,
    val pendingFeedbackLanguage: String?,
    val pendingTimezone: String?,
    val pendingDailySentenceCount: Int?,
    val pendingDailySpeakingGoalMinutes: Int?,
    val pendingDailyListeningGoalCount: Int?,
    val pendingEffectiveDate: String?,
    val minDailySentenceCount: Int,
    val maxDailySentenceCount: Int,
    val minDailySpeakingGoalMinutes: Int,
    val maxDailySpeakingGoalMinutes: Int,
    val minDailyListeningGoalCount: Int,
    val maxDailyListeningGoalCount: Int,
    val configured: Boolean,
)
