package jp.co.translacat.languagelearning.features.webaiplan.infrastructure

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

internal object WebAiPlansTable : Table("ll_web_ai_plans") {
    val id = varchar("id", 36)
    val userId = long("user_id")
    val revision = long("revision")
    val payload = text("payload_json")
    val tokenHash = varchar("token_hash", 64).nullable()
    val deleted = bool("deleted")
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(id)
}

internal object WebAiRunsTable : Table("ll_web_ai_runs") {
    val id = varchar("id", 36)
    val planId = varchar("plan_id", 36)
    val userId = long("user_id")
    val executionKey = varchar("execution_key", 128)
    val payload = text("payload_json")
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(id)
}

internal object WebAiResultsTable : Table("ll_web_ai_results") {
    val id = varchar("id", 36)
    val runId = varchar("run_id", 36)
    val userId = long("user_id")
    val submissionId = varchar("submission_id", 128)
    val requestHash = varchar("request_hash", 64)
    val status = varchar("status", 32)
    val payload = text("payload_json")
    val leaseId = varchar("lease_id", 36).nullable()
    val leaseUntil = datetime("lease_until").nullable()
    val attempts = integer("attempts")
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(id)
}

internal object WebAiItemsTable : Table("ll_web_ai_items") {
    val runId = varchar("run_id", 36)
    val itemId = varchar("item_id", 100)
    val resultId = varchar("result_id", 36)
    override val primaryKey = PrimaryKey(runId, itemId)
}
