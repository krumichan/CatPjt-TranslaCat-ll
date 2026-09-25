package jp.co.translacat.languagelearning.features.leveltest.api.dto

import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelTestSessionType
import kotlinx.serialization.Serializable

@Serializable
internal data class LevelTestStartRequestDto(
    val type: LevelTestSessionType? = null,
    val idempotencyKey: String? = null,
)
