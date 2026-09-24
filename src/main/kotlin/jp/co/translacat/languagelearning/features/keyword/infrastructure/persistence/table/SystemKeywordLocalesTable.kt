package jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/** V005 매핑이다. 테이블 생성/변경은 Flyway만 수행한다. */
internal object SystemKeywordLocalesTable : Table("language_learning_system_keyword_locale") {
    val id = long("id").autoIncrement()
    val systemKeywordId = long("system_keyword_id").references(
        SystemKeywordsTable.id,
        onDelete = ReferenceOption.RESTRICT,
        onUpdate = ReferenceOption.RESTRICT,
        fkName = "fk_ll_keyword_locale_system"
    )
    val locale = varchar("locale", 20)
    val displayName = varchar("display_name", 200)

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uk_ll_system_keyword_locale", systemKeywordId, locale)
    }
}
