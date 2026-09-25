package jp.co.translacat.languagelearning.features.resultjournal.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

/** 원본 결과 문자열은 해시 비교를 위해 직렬화 순서·공백까지 그대로 저장한다. */
internal object ResultEventsTable : Table("language_learning_result_event") {
    val eventId = varchar("event_id", 36)
    val schemaVersion = integer("schema_version")
    val sourceInstanceId = varchar("source_instance_id", 36)
    val userId = long("user_id")
    val sequence = long("stream_sequence")
    val kind = varchar("kind", 40)
    val referenceId = varchar("reference_id", 100)
    val occurredAt = varchar("occurred_at", 40)
    val payloadJson = text("payload_json")
    val payloadSha256 = varchar("payload_sha256", 64)
    val aggregationEligible = bool("aggregation_eligible")
    val receivedAt = datetime("received_at")
    override val primaryKey = PrimaryKey(eventId)

    init {
        uniqueIndex("uk_ll_result_event_sequence", sourceInstanceId, userId, sequence)
        foreignKey(
            sourceInstanceId to ResultStreamsTable.sourceInstanceId, userId to ResultStreamsTable.userId,
            name = "fk_ll_result_event_stream",
        )
    }
}
