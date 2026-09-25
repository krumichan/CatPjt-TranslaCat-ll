package jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.table

import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.table.LearnersTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.datetime

internal object GrowthProfilesTable : Table("language_learning_profile") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(LearnersTable.userId)
    val profileVersion = varchar("profile_version", 30)
    val state = varchar("state", 30)
    val baseLevelScore = double("base_level_score").nullable()
    val calibrationStartedDate = date("calibration_started_date").nullable()
    val calibrationCompletedDate = date("calibration_completed_date").nullable()
    val meaningScore = double("meaning_score").nullable()
    val grammarScore = double("grammar_score").nullable()
    val vocabularyScore = double("vocabulary_score").nullable()
    val naturalnessScore = double("naturalness_score").nullable()
    val expressionScore = double("expression_score").nullable()
    val reviewPerformance = double("review_performance").nullable()
    val normalPerformance = double("normal_performance").nullable()
    val challengePerformance = double("challenge_performance").nullable()
    val evaluationCount = integer("evaluation_count")
    val confidence = double("confidence")
    val trend = varchar("trend", 30)
    val additionalSignalsJson = text("additional_signals_json")
    val baselineCompletionId = varchar("baseline_completion_id", 36).nullable()
    val baselineCompletedAt = datetime("baseline_completed_at").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    val createdBy = varchar("created_by", 100)
    val updatedBy = varchar("updated_by", 100)
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex(userId) }
}
