package jp.co.translacat.languagelearning.features.leveltest.api.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class LevelAnswerRequestDto(
    val selectedOptionKey: String? = null,
    val selectedOptionKeys: List<String>? = null,
    val textAnswer: String? = null,
    val idempotencyKey: String? = null,
)
