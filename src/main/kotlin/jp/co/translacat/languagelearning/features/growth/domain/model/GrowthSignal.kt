package jp.co.translacat.languagelearning.features.growth.domain.model

import java.time.LocalDateTime

internal data class GrowthSignal(
    val userId: Long,
    val type: String,
    val key: String,
    val occurrenceCount: Int,
    val lastSeenAt: LocalDateTime,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)
