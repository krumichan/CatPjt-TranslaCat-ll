package jp.co.translacat.languagelearning.features.leveltest.api.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class LevelTestTaskGuidanceResponseDto(
    val providedFacts: List<String> = emptyList(),
    val requiredIntents: List<String> = emptyList(),
    val responseConstraints: List<String> = emptyList(),
)
