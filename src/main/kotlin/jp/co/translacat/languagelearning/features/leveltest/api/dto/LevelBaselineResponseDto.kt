package jp.co.translacat.languagelearning.features.leveltest.api.dto

import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelTestSessionType
import kotlinx.serialization.Serializable

/** 아직 Core에 남은 학습 기능은 이 완료 사실을 자신의 트랜잭션에서 한 번만 반영한다. */
@Serializable
internal data class LevelCompletionDto(
    val userId: Long, val sessionId: Long, val completionId: String, val sessionType: LevelTestSessionType,
    val score: Int, val proficiencyBand: String, val completedDate: String, val startedAt: String,
    val completedAt: String,
)

@Serializable
internal data class LevelBaselineResponseDto(val baseline: LevelCompletionDto?)

@Serializable
internal data class LevelCompletionsResponseDto(val completions: List<LevelCompletionDto>)
