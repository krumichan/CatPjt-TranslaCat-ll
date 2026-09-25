package jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.table

import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.table.LearnersTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.datetime

internal object KeywordMasteriesTable : Table("language_learning_keyword_mastery") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(LearnersTable.userId)
    val canonicalKey = varchar("canonical_key", 200)
    val score = double("score")
    val evaluationCount = integer("evaluation_count")
    val lastSelectedDate = date("last_selected_date").nullable()
    val selectedCount = integer("selected_count")
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    val createdBy = varchar("created_by", 100)
    val updatedBy = varchar("updated_by", 100)
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex(userId, canonicalKey) }
}
