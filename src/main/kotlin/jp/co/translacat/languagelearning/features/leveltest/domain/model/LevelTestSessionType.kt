package jp.co.translacat.languagelearning.features.leveltest.domain.model

import kotlinx.serialization.Serializable

@Serializable
internal enum class LevelTestSessionType {
    INITIAL,
    RECHECK
}
