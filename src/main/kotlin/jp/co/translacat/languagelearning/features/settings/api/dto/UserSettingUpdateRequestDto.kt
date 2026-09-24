package jp.co.translacat.languagelearning.features.settings.api.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class UserSettingUpdateRequestDto(
    val originLanguage: String? = null,
    val learningLanguage: String? = null,
    val timezone: String? = null,
    val dailySentenceCount: Int? = null,
    val dailySpeakingGoalMinutes: Int? = null,
    val speakingVoiceId: String? = null,
    val speakingPlaybackSpeed: String? = null,
    val dailyListeningGoalCount: Int? = null,
    val defaultListeningTaskTypes: List<String?>? = null,
)
