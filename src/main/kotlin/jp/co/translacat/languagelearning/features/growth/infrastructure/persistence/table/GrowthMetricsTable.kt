package jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

internal object GrowthMetricsTable : Table("language_learning_metric_history") {
    val id = long("id").autoIncrement()
    val activityId = long("activity_id").references(GrowthActivitiesTable.id)
    val metricType = varchar("metric_type", 40)
    val state = varchar("state", 30)
    val score = double("score").nullable()
    val confidence = double("confidence").nullable()
    val notEvaluableReason = varchar("not_evaluable_reason", 1000).nullable()
    val createdBy = varchar("created_by", 100)
    val updatedBy = varchar("updated_by", 100)
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex(activityId, metricType) }
}
