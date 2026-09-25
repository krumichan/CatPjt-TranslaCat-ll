package jp.co.translacat.languagelearning.features.leveltest.api.dto

import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelTestSessionStatus
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelTestSessionType
import kotlinx.serialization.Serializable

@Serializable
internal data class LevelSessionResponseDto(
    val sessionId: Long? = null,
    val sessionType: LevelTestSessionType? = null,
    val status: LevelTestSessionStatus? = null,
    val totalQuestions: Int,
    val currentQuestionNumber: Int,
    val currentComplexityBand: Int,
    val baseLevelScore: Double? = null,
    val proficiencyBand: String? = null,
    val startedAt: String? = null,
    val completedAt: String? = null,
)
