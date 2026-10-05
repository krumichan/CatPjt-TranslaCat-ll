package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.datetime

/** V020 매핑. 기존 점수형 Writing 테이블은 그대로 둔다. */
internal object CuratedCatalogTable : Table("language_learning_curated_writing_catalog") {
    val id = varchar("id", 80)
    val version = integer("version")
    val contentHash = char("content_hash", 64)
    val releaseId = varchar("release_id", 80)
    val reviewStatus = varchar("review_status", 20)
    val originLanguage = varchar("origin_language", 16)
    val learningLanguage = varchar("learning_language", 16)
    val writingType = varchar("writing_type", 30)
    val targetBand = integer("target_band")
    val semanticKey = varchar("semantic_key", 100)
    val topicKey = varchar("topic_key", 100)
    val contentJson = text("content_json")
    val approvedBy = varchar("approved_by", 100).nullable()
    val approvalManifestHash = char("approval_manifest_hash", 64).nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(id, version)
}

internal object CuratedSetsTable : Table("language_learning_curated_writing_set") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val learningDate = date("learning_date")
    val writingType = varchar("writing_type", 30)
    val policyVersion = varchar("policy_version", 50)
    val resultPolicy = varchar("result_policy", 30)
    val releaseId = varchar("release_id", 80)
    val originLanguage = varchar("origin_language", 16)
    val learningLanguage = varchar("learning_language", 16)
    val baseBand = integer("base_band")
    val status = varchar("status", 20)
    val rePractice = bool("re_practice")
    val replacementCount = integer("replacement_count")
    val targetItemCount = integer("target_item_count").default(5)
    val planRevision = integer("plan_revision").default(1)
    val plannedSlotsJson = text("planned_slots_json").nullable()
    val supplyToken = varchar("supply_token", 36).nullable()
    val supplyLeaseUntil = datetime("supply_lease_until").nullable()
    val completedSupplyToken = varchar("completed_supply_token", 36).nullable()
    val supplyStopReason = varchar("supply_stop_reason", 30).nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(id)
}

internal object CuratedItemsTable : Table("language_learning_curated_writing_item") {
    val id = long("id").autoIncrement()
    val setId = long("set_id")
    val userId = long("user_id")
    val itemOrder = integer("item_order")
    val itemPolicyVersion = varchar("item_policy_version", 50).default("curated-writing-v1")
    val difficulty = varchar("difficulty", 30)
    val targetBand = integer("target_band")
    val catalogId = varchar("catalog_id", 80)
    val catalogVersion = integer("catalog_version")
    val contentHash = char("content_hash", 64)
    val semanticKey = varchar("semantic_key", 100)
    val publicJson = text("public_json")
    val privateJson = text("private_json")
    val contentRevision = char("content_revision", 64)
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(id)
}

internal object CuratedAnswersTable : Table("language_learning_curated_writing_answer") {
    val id = long("id").autoIncrement()
    val itemId = long("item_id")
    val userId = long("user_id")
    val attemptDate = date("attempt_date")
    val answerText = text("answer_text")
    val contentRevision = char("content_revision", 64)
    val submittedAt = datetime("submitted_at")
    override val primaryKey = PrimaryKey(id)
}
