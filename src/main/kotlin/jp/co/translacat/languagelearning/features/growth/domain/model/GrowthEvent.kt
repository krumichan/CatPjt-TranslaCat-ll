package jp.co.translacat.languagelearning.features.growth.domain.model

import java.time.LocalDateTime

internal data class GrowthEvent(
    val sourceInstanceId: String,
    val eventId: String,
    val userId: Long,
    val sequence: Long,
    val occurredAt: String,
    val payloadSha256: String,
    val envelopeHash: String,
    val operations: List<GrowthOperation>,
    val eventTime: LocalDateTime,
)

internal data class GrowthOperation(val key: String, val hash: String, val change: GrowthChange)
internal data class GrowthReceipt(val eventId: String, val envelopeHash: String, val sequence: Long)
internal data class GrowthAcknowledgement(
    val sourceInstanceId: String, val eventId: String, val userId: Long,
    val sequence: Long, val payloadSha256: String, val outcome: String,
)
