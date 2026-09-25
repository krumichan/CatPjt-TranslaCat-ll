package jp.co.translacat.languagelearning.features.leveltest.api.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class LevelStatusResponseDto(
    val profileState: String? = null,
    val initialLevelTestCompleted: Boolean,
    val recheckRecommended: Boolean,
    val activeSessionId: Long? = null,
    val currentQuestionNumber: Int? = null,
    val baseLevelScore: Double? = null,
    val proficiencyBand: String? = null,
)
