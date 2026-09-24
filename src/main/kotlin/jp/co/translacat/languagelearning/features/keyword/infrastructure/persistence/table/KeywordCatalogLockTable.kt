package jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table

internal object KeywordCatalogLockTable : Table("language_learning_keyword_catalog_lock") {
    val id = integer("id")
    override val primaryKey = PrimaryKey(id)
}
