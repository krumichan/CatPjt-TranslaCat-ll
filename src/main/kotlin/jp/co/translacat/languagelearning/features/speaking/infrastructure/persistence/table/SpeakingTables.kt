package jp.co.translacat.languagelearning.features.speaking.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.datetime

internal object SpeakingTopics : Table("language_learning_speaking_topic") {
    val id = long("id").autoIncrement()
    val topicCode = varchar("topic_code", 100)
    val version = integer("version")
    val payload = text("payload_json")
    override val primaryKey = PrimaryKey(id)
}

internal object SpeakingSessions : Table("language_learning_speaking_session") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val idempotencyKey = varchar("create_idempotency_key", 200)
    val learningDate = date("learning_date")
    val snapshot = text("snapshot_json")
    val status = varchar("status", 40)
    val evaluationStatus = varchar("evaluation_status", 40)
    val completedTurns = integer("completed_turns")
    val durationSeconds = long("total_duration_seconds")
    val opening = text("opening_json")
    val summary = text("session_summary").nullable()
    val usage = text("usage_json")
    val evaluationVersion = varchar("evaluation_version", 100).nullable()
    val startedAt = datetime("started_at")
    val completedAt = datetime("completed_at").nullable()
    val lastActivityAt = datetime("last_activity_at")
    override val primaryKey = PrimaryKey(id)
}

internal object SpeakingTurns : Table("language_learning_speaking_turn") {
    val id = long("id").autoIncrement()
    val sessionId = long("session_id")
    val turnIndex = integer("turn_index")
    val idempotencyKey = varchar("idempotency_key", 200)
    val problemIndex = integer("problem_index").nullable()
    val attemptIndex = integer("attempt_index").nullable()
    val recordingRevision = integer("recording_revision")
    val status = varchar("status", 40)
    val uploadToken = varchar("upload_token", 100)
    val uploadExpiresAt = datetime("upload_expires_at")
    val content = text("content_json")
    val excluded = bool("excluded_from_evaluation")
    val failedStage = varchar("failed_stage", 40).nullable()
    val errorCode = varchar("error_code", 100).nullable()
    val errorMessage = varchar("error_message", 1000).nullable()
    val manualRetryCount = integer("manual_retry_count")
    val completedAt = datetime("completed_at").nullable()
    val executionToken = varchar("execution_token", 36).nullable()
    val executionLeaseUntil = datetime("execution_lease_until").nullable()
    override val primaryKey = PrimaryKey(id)
}

internal object SpeakingJobs : Table("language_learning_speaking_evaluation_job") {
    val id = long("id").autoIncrement()
    val sessionId = long("session_id")
    val problemIndex = integer("problem_index")
    val resultKind = varchar("result_kind", 40)
    val policyVersion = varchar("result_policy_version", 100)
    val snapshotHash = varchar("source_snapshot_hash", 128).nullable()
    val request = text("request_json")
    val status = varchar("status", 20)
    val claimToken = varchar("claim_token", 36).nullable()
    val availableAt = datetime("available_at")
    val manualRetryCount = integer("manual_retry_count")
    val recoveryCount = integer("recovery_count")
    val lastError = varchar("last_error", 100).nullable()
    override val primaryKey = PrimaryKey(id)
}

internal object SpeakingResults : Table("language_learning_speaking_result") {
    val id = long("id").autoIncrement()
    val sessionId = long("session_id")
    val problemIndex = integer("problem_index")
    val resultKind = varchar("result_kind", 40)
    val status = varchar("status", 40)
    val response = text("response_json")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(id)
}

internal object SpeakingAudio : Table("language_learning_speaking_audio") {
    val id = long("id").autoIncrement()
    val sessionId = long("session_id")
    val turnId = long("turn_id").nullable()
    val role = varchar("role", 30)
    val revision = integer("recording_revision")
    val objectKey = varchar("object_key", 500)
    val contentType = varchar("content_type", 100)
    val fileName = varchar("file_name", 300).nullable()
    val byteLength = long("byte_length")
    val sha256 = varchar("sha256", 64)
    val retentionUntil = datetime("retention_until")
    val deletedAt = datetime("deleted_at").nullable()
    val physicalDeletedAt = datetime("physical_deleted_at").nullable()
    val deleteClaimToken = varchar("delete_claim_token", 36).nullable()
    val deleteLeaseUntil = datetime("delete_lease_until").nullable()
    override val primaryKey = PrimaryKey(id)
}

internal object SpeakingUsage : Table("language_learning_speaking_ai_usage") {
    val id = long("id").autoIncrement()
    val sessionId = long("session_id")
    val turnId = long("turn_id").nullable()
    val payload = text("usage_json")
    val manualRetryAttempt = integer("manual_retry_attempt")
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(id)
}

internal object SpeakingSttReports : Table("language_learning_stt_error_report") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val sessionId = long("session_id")
    val turnId = long("turn_id")
    val reference = varchar("report_reference", 80)
    val reportType = varchar("report_type", 40)
    val status = varchar("report_status", 40)
    val expectedText = varchar("expected_text", 4000).nullable()
    val consent = bool("audio_analysis_consent")
    val retentionUntil = datetime("audio_retention_until").nullable()
    val sttMetadata = text("stt_metadata_json")
    val clientMetadata = text("client_metadata_json")
    val supportRequested = bool("support_requested")
    val supportReference = varchar("support_reference", 100).nullable()
    val createdAt = datetime("created_at")
    val resolvedAt = datetime("resolved_at").nullable()
    override val primaryKey = PrimaryKey(id)
}
