package jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table

internal object GrowthOperationsTable : Table("language_learning_growth_operation") {
    val sourceId = varchar("source_instance_id", 36)
    val userId = long("user_id")
    val key = varchar("operation_key", 160)
    val hash = varchar("operation_hash", 64)
    override val primaryKey = PrimaryKey(sourceId, userId, key)
}
