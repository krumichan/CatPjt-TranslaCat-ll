package jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.table

import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.table.LearnersTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.datetime

internal object GrowthActivitiesTable : Table("language_learning_activity") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(LearnersTable.userId)
    val activitySource = varchar("source", 30)
    val referenceId = varchar("reference_id", 100)
    val learningDate = date("learning_date")
    val title = varchar("title", 300)
    val durationSeconds = long("duration_seconds")
    val status = varchar("status", 40)
    val overallScore = double("overall_score").nullable()
    val evaluationConfidence = double("evaluation_confidence").nullable()
    val startedAt = datetime("started_at")
    val completedAt = datetime("completed_at").nullable()
    val metadataJson = text("metadata_json")
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    val createdBy = varchar("created_by", 100)
    val updatedBy = varchar("updated_by", 100)
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex(userId, activitySource, referenceId) }
}
