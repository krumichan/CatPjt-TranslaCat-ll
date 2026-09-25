package jp.co.translacat.languagelearning.features.leveltest.api.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class LevelTestHistoryDetailResponseDto(
    val summary: LevelTestHistoryItemResponseDto? = null,
    val items: List<LevelTestItemDetailResponseDto> = emptyList(),
)
