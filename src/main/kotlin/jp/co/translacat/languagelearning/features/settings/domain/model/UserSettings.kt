package jp.co.translacat.languagelearning.features.settings.domain.model

import java.time.LocalDate
import java.time.LocalDateTime

/** 조회 결과는 트랜잭션 밖에서도 안전하게 사용할 수 있는 값 객체로 반환한다. */
internal data class UserSettings(
    val id: Long,
    val userId: Long,
    val originLanguage: String?,
    val learningLanguage: String?,
    val timezone: String,
    val dailySentenceCount: Int,
    val dailySpeakingGoalMinutes: Int,
    val dailyListeningGoalCount: Int,
    val defaultListeningTaskTypesJson: String,
    val speakingVoiceId: String,
    val speakingPlaybackSpeed: String,
    val pendingOriginLanguage: String?,
    val pendingLearningLanguage: String?,
    val pendingTimezone: String?,
    val pendingDailySentenceCount: Int?,
    val pendingDailySpeakingGoalMinutes: Int?,
    val pendingDailyListeningGoalCount: Int?,
    val pendingEffectiveDate: LocalDate?,
    val createdBy: String?,
    val createdAt: LocalDateTime,
    val updatedBy: String?,
    val updatedAt: LocalDateTime,
) {
    val configured: Boolean
        get() = originLanguage != null && learningLanguage != null
}
