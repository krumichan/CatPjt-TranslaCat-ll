package jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.table

import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.table.LearnersTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.datetime

internal object GrowthSignalsTable : Table("language_learning_profile_signal") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(LearnersTable.userId)
    val type = varchar("signal_type", 40)
    val key = varchar("signal_key", 300)
    val occurrenceCount = integer("occurrence_count")
    val lastSeenAt = datetime("last_seen_at")
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    val createdBy = varchar("created_by", 100)
    val updatedBy = varchar("updated_by", 100)
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex(userId, type, key) }
}
