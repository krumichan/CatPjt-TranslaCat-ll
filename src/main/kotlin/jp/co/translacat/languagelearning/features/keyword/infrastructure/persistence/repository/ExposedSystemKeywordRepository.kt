package jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.repository

import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordType
import jp.co.translacat.languagelearning.features.keyword.domain.model.SystemKeyword
import jp.co.translacat.languagelearning.features.keyword.domain.repository.SystemKeywordRepository
import jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.table.SystemKeywordsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDateTime

internal class ExposedSystemKeywordRepository(private val requireTransaction: () -> Unit) : SystemKeywordRepository {
    override fun findAll(): List<SystemKeyword> {
        requireTransaction()
        return SystemKeywordsTable.selectAll()
            .orderBy(SystemKeywordsTable.sortOrder to SortOrder.ASC, SystemKeywordsTable.id to SortOrder.ASC)
            .forUpdate()
            .map(::toModel)
    }

    override fun save(keyword: SystemKeyword, actorId: Long, now: LocalDateTime): SystemKeyword {
        requireTransaction()
        val value = keyword
        val id = if (value.id == 0L) {
            SystemKeywordsTable.insert {
                it[SystemKeywordsTable.text] = value.text
                it[SystemKeywordsTable.normalizedText] = value.normalizedText
                it[SystemKeywordsTable.type] = value.type.name
                it[SystemKeywordsTable.canonicalKey] = value.canonicalKey
                it[SystemKeywordsTable.parentKeywordId] = value.parentKeywordId
                it[SystemKeywordsTable.sortOrder] = value.sortOrder
                it[SystemKeywordsTable.active] = value.active
                it[SystemKeywordsTable.createdBy] = actorId.toString()
                it[SystemKeywordsTable.createdAt] = now
                it[SystemKeywordsTable.updatedBy] = actorId.toString()
                it[SystemKeywordsTable.updatedAt] = now
            }[SystemKeywordsTable.id]
        } else {
            val count = SystemKeywordsTable.update({ SystemKeywordsTable.id eq value.id }) {
                it[SystemKeywordsTable.text] = value.text
                it[SystemKeywordsTable.normalizedText] = value.normalizedText
                it[SystemKeywordsTable.type] = value.type.name
                it[SystemKeywordsTable.canonicalKey] = value.canonicalKey
                it[SystemKeywordsTable.parentKeywordId] = value.parentKeywordId
                it[SystemKeywordsTable.sortOrder] = value.sortOrder
                it[SystemKeywordsTable.active] = value.active
                it[SystemKeywordsTable.updatedBy] = actorId.toString()
                it[SystemKeywordsTable.updatedAt] = now
            }
            check(count in 0..1) { "키워드 갱신 행 수가 올바르지 않습니다." }
            value.id
        }
        val saved =
            SystemKeywordsTable.selectAll().where { SystemKeywordsTable.id eq id }.forUpdate().single().let(::toModel)
        check(saved == value.copy(id = id)) { "키워드 저장 결과가 요청과 다릅니다." }
        return saved
    }

    private fun toModel(row: ResultRow) = SystemKeyword(
        id = row[SystemKeywordsTable.id],
        text = row[SystemKeywordsTable.text],
        normalizedText = row[SystemKeywordsTable.normalizedText],
        type = KeywordType.valueOf(row[SystemKeywordsTable.type]),
        canonicalKey = row[SystemKeywordsTable.canonicalKey],
        parentKeywordId = row[SystemKeywordsTable.parentKeywordId],
        sortOrder = row[SystemKeywordsTable.sortOrder],
        active = row[SystemKeywordsTable.active],
    )
}
