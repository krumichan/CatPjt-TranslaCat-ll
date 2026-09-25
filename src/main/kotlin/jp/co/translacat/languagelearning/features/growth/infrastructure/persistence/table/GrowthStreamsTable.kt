package jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table

internal object GrowthStreamsTable : Table("language_learning_growth_stream") {
    val sourceId = varchar("source_instance_id", 36)
    val userId = long("user_id")
    val sequence = long("last_sequence")
    override val primaryKey = PrimaryKey(sourceId, userId)
}
