package jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.table

import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.table.LearnersTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.datetime

/** V005 매핑이다. 테이블 생성/변경은 Flyway만 수행한다. */
internal object CustomKeywordsTable : Table("language_learning_custom_keyword") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(
        LearnersTable.userId,
        onDelete = ReferenceOption.RESTRICT,
        onUpdate = ReferenceOption.RESTRICT,
        fkName = "fk_ll_custom_keyword_learner",
    )
    val text = varchar("text", 200)
    val normalizedText = varchar("normalized_text", 200)
    val type = varchar("keyword_type", 30)
    val canonicalKey = varchar("canonical_key", 200).nullable()
    val parentSystemKeywordId = long("parent_system_keyword_id").references(
        SystemKeywordsTable.id,
        onDelete = ReferenceOption.RESTRICT,
        onUpdate = ReferenceOption.RESTRICT,
        fkName = "fk_ll_custom_keyword_parent",
    ).nullable()
    val active = bool("active")
    val availableFrom = date("available_from")
    val pendingText = varchar("pending_text", 200).nullable()
    val pendingNormalizedText = varchar("pending_normalized_text", 200).nullable()
    val pendingType = varchar("pending_keyword_type", 30).nullable()
    val pendingCanonicalKey = varchar("pending_canonical_key", 200).nullable()
    val pendingParentSystemKeywordId = long("pending_parent_system_keyword_id").references(
        SystemKeywordsTable.id,
        onDelete = ReferenceOption.RESTRICT,
        onUpdate = ReferenceOption.RESTRICT,
        fkName = "fk_ll_custom_keyword_pending_parent",
    ).nullable()
    val pendingParentChanged = bool("pending_parent_changed")
    val pendingActive = bool("pending_active").nullable()
    val pendingEffectiveDate = date("pending_effective_date").nullable()
    val createdBy = varchar("created_by", 50).nullable()
    val createdAt = datetime("created_at")
    val updatedBy = varchar("updated_by", 50).nullable()
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uk_ll_custom_keyword_user_normalized_type", userId, normalizedText, type)
        index("idx_ll_custom_keyword_user_active", false, userId, active)
    }
}
