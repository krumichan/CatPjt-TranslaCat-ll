package jp.co.translacat.languagelearning.features.leveltest.api.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class LevelTestDomainScoresResponseDto(
    val vocabulary: Int? = null,
    val grammar: Int? = null,
    val reading: Int? = null,
    val listening: Int? = null,
    val writing: Int? = null,
    val speaking: Int? = null,
)
