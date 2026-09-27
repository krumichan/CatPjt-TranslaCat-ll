package jp.co.translacat.languagelearning.features.practice.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.datetime

internal object PracticeSets : Table("language_learning_practice_set") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val learningDate = date("learning_date")
    val domain = varchar("domain", 30)
    val mode = varchar("mode", 40)
    val complexityBand = integer("complexity_band")
    val questionCount = integer("question_count")
    val requestJson = text("request_json")
    val status = varchar("status", 30)
    val generationStatus = varchar("generation_status", 30)
    val generationToken = varchar("generation_token", 36).nullable()
    val generationLeaseUntil = datetime("generation_lease_until").nullable()
    val nextAttemptAt = datetime("next_attempt_at").nullable()
    val retryCount = integer("retry_count")
    val failureCode = varchar("failure_code", 100).nullable()
    val officialScore = double("official_score").nullable()
    val promptVersion = varchar("prompt_version", 100).nullable()
    val startedAt = datetime("started_at")
    val completedAt = datetime("completed_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

internal object PracticeQuestions : Table("language_learning_practice_question") {
    val id = long("id").autoIncrement()
    val setId = long("practice_set_id")
    val order = integer("order_no")
    val contentJson = text("content_json")
    override val primaryKey = PrimaryKey(id)
}

internal object PracticeAttempts : Table("language_learning_practice_attempt") {
    val id = long("id").autoIncrement()
    val questionId = long("question_id")
    val attemptNo = integer("attempt_no")
    val answerJson = text("answer_json")
    val correct = bool("correct")
    val submittedAt = datetime("submitted_at")
    override val primaryKey = PrimaryKey(id)
}

internal object PracticeMetrics : Table("language_learning_practice_metric") {
    val setId = long("practice_set_id")
    val skillTag = varchar("skill_tag", 80)
    val score = double("score")
    val sampleCount = integer("sample_count")
    override val primaryKey = PrimaryKey(setId, skillTag)
}

internal object VocabularyMasteries : Table("language_learning_vocabulary_mastery") {
    val userId = long("user_id")
    val canonicalKey = varchar("canonical_key", 200)
    val displayExpression = varchar("display_expression", 300)
    val score = double("score")
    val evaluationCount = integer("evaluation_count")
    val selectedCount = integer("selected_count")
    val lastSelectedDate = date("last_selected_date").nullable()
    override val primaryKey = PrimaryKey(userId, canonicalKey)
}
