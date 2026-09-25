package jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.repository

import jp.co.translacat.languagelearning.features.keyword.domain.model.CustomKeyword
import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordType
import jp.co.translacat.languagelearning.features.keyword.domain.repository.CustomKeywordRepository
import jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.table.CustomKeywordsTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDateTime

internal class ExposedCustomKeywordRepository(private val requireTransaction: () -> Unit) : CustomKeywordRepository {
    override fun findForUser(userId: Long): List<CustomKeyword> {
        requireTransaction()
        return CustomKeywordsTable.selectAll()
            .where { CustomKeywordsTable.userId eq userId }
            .orderBy(CustomKeywordsTable.id to SortOrder.ASC)
            .forUpdate()
            .map(::toModel)
    }

    override fun save(keyword: CustomKeyword, actorId: Long, now: LocalDateTime): CustomKeyword {
        requireTransaction()
        val value = keyword
        val id = if (value.id == 0L) {
            CustomKeywordsTable.insert {
                it[CustomKeywordsTable.userId] = value.userId
                it[CustomKeywordsTable.text] = value.text
                it[CustomKeywordsTable.normalizedText] = value.normalizedText
                it[CustomKeywordsTable.type] = value.type.name
                it[CustomKeywordsTable.canonicalKey] = value.canonicalKey
                it[CustomKeywordsTable.parentSystemKeywordId] = value.parentSystemKeywordId
                it[CustomKeywordsTable.active] = value.active
                it[CustomKeywordsTable.availableFrom] = value.availableFrom
                it[CustomKeywordsTable.pendingText] = value.pendingText
                it[CustomKeywordsTable.pendingNormalizedText] = value.pendingNormalizedText
                it[CustomKeywordsTable.pendingType] = value.pendingType?.name
                it[CustomKeywordsTable.pendingCanonicalKey] = value.pendingCanonicalKey
                it[CustomKeywordsTable.pendingParentSystemKeywordId] = value.pendingParentSystemKeywordId
                it[CustomKeywordsTable.pendingParentChanged] = value.pendingParentChanged
                it[CustomKeywordsTable.pendingActive] = value.pendingActive
                it[CustomKeywordsTable.pendingEffectiveDate] = value.pendingEffectiveDate
                it[CustomKeywordsTable.createdBy] = actorId.toString()
                it[CustomKeywordsTable.createdAt] = now
                it[CustomKeywordsTable.updatedBy] = actorId.toString()
                it[CustomKeywordsTable.updatedAt] = now
            }[CustomKeywordsTable.id]
        } else {
            val count =
                CustomKeywordsTable.update(
                    { (CustomKeywordsTable.id eq value.id) and (CustomKeywordsTable.userId eq value.userId) },
                ) {
                    it[CustomKeywordsTable.text] = value.text
                    it[CustomKeywordsTable.normalizedText] = value.normalizedText
                    it[CustomKeywordsTable.type] = value.type.name
                    it[CustomKeywordsTable.canonicalKey] = value.canonicalKey
                    it[CustomKeywordsTable.parentSystemKeywordId] = value.parentSystemKeywordId
                    it[CustomKeywordsTable.active] = value.active
                    it[CustomKeywordsTable.availableFrom] = value.availableFrom
                    it[CustomKeywordsTable.pendingText] = value.pendingText
                    it[CustomKeywordsTable.pendingNormalizedText] = value.pendingNormalizedText
                    it[CustomKeywordsTable.pendingType] = value.pendingType?.name
                    it[CustomKeywordsTable.pendingCanonicalKey] = value.pendingCanonicalKey
                    it[CustomKeywordsTable.pendingParentSystemKeywordId] = value.pendingParentSystemKeywordId
                    it[CustomKeywordsTable.pendingParentChanged] = value.pendingParentChanged
                    it[CustomKeywordsTable.pendingActive] = value.pendingActive
                    it[CustomKeywordsTable.pendingEffectiveDate] = value.pendingEffectiveDate
                    it[CustomKeywordsTable.updatedBy] = actorId.toString()
                    it[CustomKeywordsTable.updatedAt] = now
                }
            check(count in 0..1) { "키워드 갱신 행 수가 올바르지 않습니다." }
            value.id
        }
        val saved =
            CustomKeywordsTable.selectAll().where { CustomKeywordsTable.id eq id }.forUpdate().single().let(::toModel)
        check(saved == value.copy(id = id)) { "키워드 저장 결과가 요청과 다릅니다." }
        return saved
    }

    override fun referencesParent(parentId: Long): Boolean {
        requireTransaction()
        return CustomKeywordsTable.selectAll().where {
            (CustomKeywordsTable.parentSystemKeywordId eq parentId) or (CustomKeywordsTable.pendingParentSystemKeywordId eq parentId)
        }.limit(1).forUpdate().any()
    }

    private fun toModel(row: ResultRow) = CustomKeyword(
        id = row[CustomKeywordsTable.id],
        userId = row[CustomKeywordsTable.userId],
        text = row[CustomKeywordsTable.text],
        normalizedText = row[CustomKeywordsTable.normalizedText],
        type = KeywordType.valueOf(row[CustomKeywordsTable.type]),
        canonicalKey = row[CustomKeywordsTable.canonicalKey],
        parentSystemKeywordId = row[CustomKeywordsTable.parentSystemKeywordId],
        active = row[CustomKeywordsTable.active],
        availableFrom = row[CustomKeywordsTable.availableFrom],
        pendingText = row[CustomKeywordsTable.pendingText],
        pendingNormalizedText = row[CustomKeywordsTable.pendingNormalizedText],
        pendingType = row[CustomKeywordsTable.pendingType]?.let(KeywordType::valueOf),
        pendingCanonicalKey = row[CustomKeywordsTable.pendingCanonicalKey],
        pendingParentSystemKeywordId = row[CustomKeywordsTable.pendingParentSystemKeywordId],
        pendingParentChanged = row[CustomKeywordsTable.pendingParentChanged],
        pendingActive = row[CustomKeywordsTable.pendingActive],
        pendingEffectiveDate = row[CustomKeywordsTable.pendingEffectiveDate],
    )
}
