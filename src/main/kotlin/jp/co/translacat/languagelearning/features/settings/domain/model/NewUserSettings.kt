package jp.co.translacat.languagelearning.features.settings.domain.model

import java.time.LocalDateTime

/** 신규 행만을 위한 입력이다. 이미 저장된 설정이나 pending 값에 적용하지 않는다. */
internal data class NewUserSettings(
    val userId: Long,
    val dailySentenceCount: Int,
    val dailySpeakingGoalMinutes: Int,
    val dailyListeningGoalCount: Int,
    val nowUtc: LocalDateTime,
) {
    val timezone: String = "Asia/Tokyo"
    val defaultListeningTaskTypesJson: String = "[\"DICTATION\"]"
    val speakingVoiceId: String = "marin"
    val speakingPlaybackSpeed: String = "NORMAL"

    init {
        require(userId > 0) { "userId는 양수여야 합니다." }
        require(dailySentenceCount > 0 && dailySpeakingGoalMinutes > 0 && dailyListeningGoalCount > 0) {
            "학습 목표는 양수여야 합니다."
        }
    }

    companion object {
        fun fromPolicy(userId: Long, policy: InitialSettingsPolicy, nowUtc: LocalDateTime) = NewUserSettings(
            userId = userId,
            dailySentenceCount = policy.writing.defaultValue,
            dailySpeakingGoalMinutes = policy.speaking.defaultValue,
            dailyListeningGoalCount = policy.listening.defaultValue,
            nowUtc = nowUtc,
        )
    }
}
