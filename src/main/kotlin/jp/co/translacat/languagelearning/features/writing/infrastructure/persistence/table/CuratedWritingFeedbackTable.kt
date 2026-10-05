package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

/** V022: 답안별 결과와 실패 상태를 저장하되 점수형 평가 행은 만들지 않는다. */
internal object CuratedWritingFeedbackTable : Table("language_learning_curated_writing_feedback") {
    val id = long("id").autoIncrement()
    val answerId = long("answer_id")
    val itemId = long("item_id")
    val setId = long("set_id")
    val userId = long("user_id")
    val policyVersion = varchar("policy_version", 50)
    val status = varchar("status", 20)
    val answerHash = char("answer_hash", 64)
    val contentRevision = char("content_revision", 64)
    val sourceHash = char("source_hash", 64)
    val requestHash = char("request_hash", 64)
    val claimToken = varchar("claim_token", 36).nullable()
    val claimUntil = datetime("claim_until").nullable()
    val attemptCount = integer("attempt_count")
    val resultJson = text("result_json").nullable()
    val resultHash = char("result_hash", 64).nullable()
    val failureCode = varchar("failure_code", 80).nullable()
    val provider = varchar("provider", 30).nullable()
    val model = varchar("model", 80).nullable()
    val inputTokens = integer("input_tokens").nullable()
    val outputTokens = integer("output_tokens").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(id)
}
