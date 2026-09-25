package jp.co.translacat.languagelearning.features.leveltest.api.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class LevelTestOptionResponseDto(
    val key: String? = null,
    val text: String? = null,
)
