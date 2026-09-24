package jp.co.translacat.languagelearning.features.settings.domain.model

import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskType

/** null과 누락은 모두 미변경이다. 기존 BE PATCH 계약에는 null로 지우는 동작이 없다. */
internal data class UserSettingsChange(
    val originLanguage: String? = null,
    val learningLanguage: String? = null,
    val timezone: String? = null,
    val dailySentenceCount: Int? = null,
    val dailySpeakingGoalMinutes: Int? = null,
    val speakingVoiceId: String? = null,
    val speakingPlaybackSpeed: String? = null,
    val dailyListeningGoalCount: Int? = null,
    val defaultListeningTaskTypes: List<ListeningTaskType?>? = null,
)
