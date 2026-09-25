package jp.co.translacat.languagelearning.features.leveltest.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

/** V007의 매핑이다. 테이블 생성과 변경은 Flyway만 수행한다. */
internal object LevelResponsesTable : Table("language_learning_level_test_response") {
    val id = long("id").autoIncrement()
    val itemId = long("item_id").references(LevelItemsTable.id, fkName = "fk_ll_level_response_item")
        .uniqueIndex("uk_ll_level_response_item")
    val idempotencyKey = varchar("idempotency_key", 200)
    val fingerprint = varchar("fingerprint", 64)
    val optionKey = varchar("selected_option_key", 100).nullable()
    val optionKeys = text("selected_option_keys_json")
    val textAnswer = text("text_answer").nullable()
    val audioKey = varchar("audio_object_key", 100).nullable()
    val contentType = varchar("audio_content_type", 100).nullable()
    val durationMs = integer("audio_duration_ms").nullable()
    val retentionUntil = datetime("audio_retention_until").nullable()
    val submittedAt = datetime("submitted_at")
    val manualRetryCount = integer("manual_evaluation_retry_count")
    val revision = integer("submission_revision")
    override val primaryKey = PrimaryKey(id)
}
