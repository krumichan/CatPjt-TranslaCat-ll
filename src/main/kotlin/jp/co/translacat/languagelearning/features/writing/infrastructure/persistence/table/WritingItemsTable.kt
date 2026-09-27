package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

/** V009 mapping; Flyway owns the DDL and composite owner FK. */
internal object WritingItemsTable : Table("language_learning_daily_item") {
    val id = long("id").autoIncrement()
    val dailySetId = long("daily_set_id")
    val userId = long("user_id")
    val itemOrder = integer("item_order")
    val difficulty = varchar("difficulty", 30)
    val originText = text("origin_text")
    val keywordsJson = text("keywords_json")
    val focusMetricsJson = text("focus_metrics_json")
    val focusReason = text("focus_reason")
    val providedFactsJson = text("provided_facts_json").nullable()
    val requiredIntentsJson = text("required_intents_json").nullable()
    val responseConstraintsJson = text("response_constraints_json").nullable()
    val languageComplexityBand = integer("language_complexity_band").nullable()
    val diversityMetadataJson = text("diversity_metadata_json").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(id)
}
