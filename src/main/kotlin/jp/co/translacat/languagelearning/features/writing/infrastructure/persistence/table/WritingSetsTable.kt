package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table

import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.table.LearnersTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.datetime

/** V009 매핑. DDL은 Flyway만 수행한다. */
internal object WritingSetsTable : Table("language_learning_daily_set") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(
        LearnersTable.userId, onDelete = ReferenceOption.RESTRICT, onUpdate = ReferenceOption.RESTRICT,
        fkName = "fk_ll_w_set_learner",
    )
    val learningDate = date("learning_date")
    val writingType = varchar("writing_type", 30)
    val snapshotId = varchar("snapshot_id", 100)
    val sentenceCount = integer("sentence_count")
    val status = varchar("status", 30)
    val snapshotJson = text("snapshot_json")
    val promptVersion = varchar("prompt_version", 100).nullable()
    val regenerationCount = integer("regeneration_count")
    val completedAt = datetime("completed_at").nullable()
    val failureMessage = varchar("failure_message", 1000).nullable()
    val generationToken = varchar("generation_token", 36).nullable()
    val generationLeaseUntil = datetime("generation_lease_until").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(id)
}
