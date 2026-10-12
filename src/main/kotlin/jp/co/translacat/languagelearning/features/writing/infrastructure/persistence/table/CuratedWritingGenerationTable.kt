package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

internal object CuratedGenerationTable : Table("language_learning_curated_writing_generation") {
    val id = varchar("id", 36)
    val userId = long("user_id")
    val requestKey = varchar("request_key", 80)
    val requestHash = char("request_hash", 64)
    val requestJson = text("request_json")
    val releaseId = varchar("release_id", 80)
    val policyVersion = varchar("policy_version", 50)
    val catalogId = varchar("catalog_id", 80)
    val status = varchar("status", 20)
    val outputJson = text("output_json").nullable()
    val manifestJson = text("manifest_json").nullable()
    val failureCode = varchar("failure_code", 80).nullable()
    val provider = varchar("provider", 30).nullable()
    val model = varchar("model", 80).nullable()
    val inputTokens = integer("input_tokens").nullable()
    val outputTokens = integer("output_tokens").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(id)
}
