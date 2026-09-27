package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

internal object WritingEvaluationsTable : Table("language_learning_writing_evaluation") {
    val id = long("id").autoIncrement()
    val answerId = long("answer_id")
    val userId = long("user_id")
    val evaluationContext = varchar("evaluation_context", 30)
    val status = varchar("status", 20)
    val overallScore = integer("overall_score").nullable()
    val meaningScore = integer("meaning_score").nullable()
    val grammarScore = integer("grammar_score").nullable()
    val vocabularyScore = integer("vocabulary_score").nullable()
    val naturalnessScore = integer("naturalness_score").nullable()
    val expressionScore = integer("expression_score").nullable()
    val strengthsJson = text("strengths_json").nullable()
    val weaknessesJson = text("weaknesses_json").nullable()
    val correctionsJson = text("corrections_json").nullable()
    val recommendedAnswersJson = text("recommended_answers_json").nullable()
    val explanationJson = text("explanation_json").nullable()
    val profileSignalsJson = text("profile_signals_json").nullable()
    val evaluationRubricVersion = varchar("evaluation_rubric_version", 100).nullable()
    val scoringPolicyVersion = varchar("scoring_policy_version", 100).nullable()
    val promptVersion = varchar("prompt_version", 100).nullable()
    val evaluatedAt = datetime("evaluated_at").nullable()
    val failureMessage = varchar("failure_message", 1000).nullable()
    val evaluationToken = varchar("evaluation_token", 36).nullable()
    val evaluationLeaseUntil = datetime("evaluation_lease_until").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(id)
}
