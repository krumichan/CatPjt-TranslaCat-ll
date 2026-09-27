package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.datetime

internal object WritingAnswersTable : Table("language_learning_writing_answer") {
    val id = long("id").autoIncrement()
    val dailyItemId = long("daily_item_id")
    val userId = long("user_id")
    val attemptDate = date("attempt_date")
    val answerText = text("answer_text")
    val submittedAt = datetime("submitted_at")
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(id)
}
