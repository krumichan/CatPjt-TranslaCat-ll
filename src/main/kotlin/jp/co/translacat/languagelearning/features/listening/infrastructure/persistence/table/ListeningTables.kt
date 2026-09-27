package jp.co.translacat.languagelearning.features.listening.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.datetime

internal object ListeningRecordIds : Table("language_learning_listening_record_id") {
    val id = long("id").autoIncrement()
    override val primaryKey = PrimaryKey(id)
}

internal object ListeningSets : Table("language_learning_listening_set") {
    val id = long("id")
    val userId = long("user_id")
    val learningDate = date("learning_date")
    val learningLanguage = varchar("learning_language", 20)
    val learningMode = varchar("learning_mode", 30)
    val status = varchar("status", 30)
    val revision = long("revision")
    val state = text("state_json")
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(id)
}

internal object ListeningSessions : Table("language_learning_listening_session") {
    val id = long("id")
    val userId = long("user_id")
    val setId = long("set_id")
    val idempotencyKey = varchar("idempotency_key", 200)
    val status = varchar("status", 30)
    val revision = long("revision")
    val state = text("state_json")
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(id)
}

internal object ListeningJobs : Table("language_learning_listening_job") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val aggregateId = long("aggregate_id")
    val type = varchar("job_type", 30)
    val key = varchar("idempotency_key", 200)
    val payload = text("payload_json")
    val status = varchar("status", 30)
    val token = varchar("lease_token", 36).nullable()
    val leaseUntil = datetime("lease_until").nullable()
    val errorCode = varchar("error_code", 80).nullable()
    val attemptCount = integer("attempt_count").default(0)
    val availableAt = datetime("available_at")
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(id)
}

internal object ListeningAudios : Table("language_learning_listening_audio") {
    val id = long("id")
    val userId = long("user_id")
    val ownerId = long("owner_id")
    val revision = long("revision")
    val contentType = varchar("content_type", 100)
    val checksum = varchar("checksum", 64)
    val bytes = blob("audio_bytes").nullable()
    val retentionUntil = datetime("retention_until")
    val deletedAt = datetime("deleted_at").nullable()
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(id)
}

internal object ListeningMetricHistories : Table("language_learning_listening_metric_history") {
    val id = long("id")
    val userId = long("user_id")
    val language = varchar("learning_language", 20)
    val metric = varchar("metric", 40)
    val evaluationId = varchar("reference_evaluation_id", 160)
    val state = text("state_json")
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(id)
}

internal object ListeningRecommendations : Table("language_learning_listening_recommendation") {
    val id = long("id")
    val userId = long("user_id")
    val language = varchar("learning_language", 20)
    val metric = varchar("metric", 40)
    val calculationVersion = varchar("calculation_version", 100)
    val state = text("state_json")
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(id)
}
