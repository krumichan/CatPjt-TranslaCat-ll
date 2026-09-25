package jp.co.translacat.languagelearning.features.leveltest.api.dto

import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelTestSessionType
import kotlinx.serialization.Serializable

@Serializable
internal data class LevelTestResultResponseDto(
    val sessionId: Long? = null,
    val sessionType: LevelTestSessionType? = null,
    val overallScore: Int? = null,
    val proficiencyBand: String? = null,
    val domainScores: LevelTestDomainScoresResponseDto? = null,
    val recommendedDifficulty: String? = null,
    val completedAt: String? = null,
)
