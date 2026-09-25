package jp.co.translacat.languagelearning.features.leveltest.domain.model

import kotlinx.serialization.Serializable

@Serializable
internal enum class LevelTestSessionStatus {
    IN_PROGRESS,
    EVALUATING,
    COMPLETED,
    FAILED,
    ABANDONED
}
