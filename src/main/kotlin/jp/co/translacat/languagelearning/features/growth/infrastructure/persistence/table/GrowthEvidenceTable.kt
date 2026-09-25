package jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.table

import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.table.LearnersTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

internal object GrowthEvidenceTable : Table("language_learning_profile_evidence") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(LearnersTable.userId)
    val evidenceSource = varchar("source", 30)
    val metricType = varchar("metric_type", 40).nullable()
    val patternKey = varchar("pattern_key", 300)
    val direction = varchar("direction", 30)
    val evidenceCount = integer("evidence_count")
    val weightedEvidence = double("weighted_evidence")
    val averageConfidence = double("average_confidence")
    val recommendedFocus = varchar("recommended_focus", 1000).nullable()
    val lastSeenAt = datetime("last_seen_at")
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    val createdBy = varchar("created_by", 100)
    val updatedBy = varchar("updated_by", 100)
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex(userId, evidenceSource, patternKey, direction) }
}
