package jp.co.translacat.languagelearning.shared.diversity

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

internal object GenerationFingerprintsTable : Table("language_learning_generation_fingerprint") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val sourceType = varchar("source_type", 30)
    val sourceId = varchar("source_id", 100)
    val learningLanguage = varchar("learning_language", 20)
    val generatedAt = datetime("generated_at")
    val contentHash = varchar("content_hash", 100)
    val similarityKey = varchar("similarity_key", 500).nullable()
    val contentExcerpt = varchar("content_excerpt", 500).nullable()
    val scenarioCategory = varchar("scenario_category", 50).nullable()
    val communicativeIntent = varchar("communicative_intent", 50).nullable()
    val taskArchetype = varchar("task_archetype", 100).nullable()
    val grammarFocusJson = text("grammar_focus_json").nullable()
    val semanticSummary = varchar("semantic_summary", 1000).nullable()
    val policyVersion = varchar("policy_version", 100)
    override val primaryKey = PrimaryKey(id)
}
