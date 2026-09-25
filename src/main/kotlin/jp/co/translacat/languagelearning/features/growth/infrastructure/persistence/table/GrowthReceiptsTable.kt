package jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

internal object GrowthReceiptsTable : Table("language_learning_growth_receipt") {
    val eventId = varchar("event_id", 36)
    val sourceId = varchar("source_instance_id", 36)
    val userId = long("user_id")
    val sequence = long("stream_sequence")
    val envelopeHash = varchar("envelope_hash", 64)
    val occurredAt = varchar("occurred_at", 40)
    val receivedAt = datetime("received_at")
    override val primaryKey = PrimaryKey(eventId)
}
