package jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.repository

import jp.co.translacat.languagelearning.features.keyword.domain.model.SystemKeywordLocale
import jp.co.translacat.languagelearning.features.keyword.domain.repository.SystemKeywordLocaleRepository
import jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.table.SystemKeywordLocalesTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll

internal class ExposedSystemKeywordLocaleRepository(private val requireTransaction: () -> Unit) :
    SystemKeywordLocaleRepository {
    override fun findAll(): List<SystemKeywordLocale> {
        requireTransaction()
        return SystemKeywordLocalesTable.selectAll().orderBy(SystemKeywordLocalesTable.id to SortOrder.ASC).map { row ->
            SystemKeywordLocale(
                row[SystemKeywordLocalesTable.systemKeywordId],
                row[SystemKeywordLocalesTable.locale],
                row[SystemKeywordLocalesTable.displayName],
            )
        }
    }
}
