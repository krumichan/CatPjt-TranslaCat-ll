package jp.co.translacat.languagelearning.features.settings.domain.model

import java.time.LocalDateTime

/** 동일 Core outbox의 증가하는 ID를 기준으로 마지막 전달만 보관한다. */
internal data class SettingsSelectionDelivery(
    val userId: Long,
    val lastEventId: Long,
    val baseRevision: LocalDateTime,
    val appliedRevision: LocalDateTime?,
)

internal enum class SelectionDeliveryStatus { APPLIED, DUPLICATE, SUPERSEDED }
