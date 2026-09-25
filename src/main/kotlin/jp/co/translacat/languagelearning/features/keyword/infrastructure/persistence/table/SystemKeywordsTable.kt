package jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

/** V005 매핑이다. 테이블 생성/변경은 Flyway만 수행한다. */
internal object SystemKeywordsTable : Table("language_learning_system_keyword") {
    val id = long("id").autoIncrement()
    val text = varchar("text", 200)
    val normalizedText = varchar("normalized_text", 200)
    val type = varchar("keyword_type", 30)
    val canonicalKey = varchar("canonical_key", 200).nullable()
    val parentKeywordId = long("parent_keyword_id").references(
        SystemKeywordsTable.id,
        onDelete = ReferenceOption.RESTRICT,
        onUpdate = ReferenceOption.RESTRICT,
        fkName = "fk_ll_system_keyword_parent",
    ).nullable()
    val sortOrder = integer("sort_order")
    val active = bool("active")
    val createdBy = varchar("created_by", 50).nullable()
    val createdAt = datetime("created_at")
    val updatedBy = varchar("updated_by", 50).nullable()
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uk_ll_system_keyword_normalized_type", normalizedText, type)
    }
}
