package jp.co.translacat.languagelearning.features.settings.api.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class SelectionDeliveryRequestDto(val eventId: Long, val expectedRevision: String, val taskTypes: List<String?>)

@Serializable
internal data class SelectionDeliveryResponseDto(val status: String)
