package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.repository

import jp.co.translacat.languagelearning.features.writing.domain.model.NewWritingItem
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingItem
import jp.co.translacat.languagelearning.features.writing.domain.repository.WritingItemRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDateTime
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.WritingAnswersTable as Answers
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.WritingItemsTable as Items

internal class ExposedWritingItemRepository(private val requireTransaction: () -> Unit) : WritingItemRepository {
    override fun firstMissingOrder(userId: Long, setId: Long, sentenceCount: Int): Int {
        requireTransaction()
        val orders = Items.selectAll().where { (Items.userId eq userId) and (Items.dailySetId eq setId) }
            .map { it[Items.itemOrder] }.toSet()
        return (1..sentenceCount).firstOrNull { it !in orders } ?: sentenceCount + 1
    }

    override fun count(userId: Long, setId: Long): Int {
        requireTransaction()
        return Items.selectAll().where { (Items.userId eq userId) and (Items.dailySetId eq setId) }.count().toInt()
    }

    override fun insert(userId: Long, setId: Long, item: NewWritingItem, nowUtc: LocalDateTime): Long {
        requireTransaction()
        return Items.insert {
            it[Items.userId] = userId
            it[dailySetId] = setId
            it[itemOrder] = item.order
            it[difficulty] = item.difficulty.name
            it[originText] = item.originText
            it[keywordsJson] = json(item.keywords)
            it[focusMetricsJson] = json(item.focusMetrics)
            it[focusReason] = item.focusReason
            it[providedFactsJson] = json(item.providedFacts)
            it[requiredIntentsJson] = json(item.requiredIntents)
            it[responseConstraintsJson] = json(item.responseConstraints)
            it[languageComplexityBand] = item.languageComplexityBand
            it[diversityMetadataJson] = item.diversityMetadataJson
            it[createdAt] = nowUtc
            it[updatedAt] = nowUtc
        }[Items.id]
    }

    override fun list(userId: Long, setId: Long): List<WritingItem> {
        requireTransaction()
        return Items.selectAll().where { (Items.userId eq userId) and (Items.dailySetId eq setId) }
            .orderBy(Items.itemOrder).map(::toModel)
    }

    override fun find(userId: Long, itemId: Long): WritingItem? {
        requireTransaction()
        return Items.selectAll().where { (Items.userId eq userId) and (Items.id eq itemId) }
            .singleOrNull()?.let(::toModel)
    }

    override fun hasAnswer(userId: Long, itemId: Long): Boolean {
        requireTransaction()
        return !Answers.selectAll().where { (Answers.userId eq userId) and (Answers.dailyItemId eq itemId) }
            .limit(1).empty()
    }

    override fun replace(userId: Long, itemId: Long, item: NewWritingItem, nowUtc: LocalDateTime): Boolean {
        requireTransaction()
        return Items.update({ (Items.id eq itemId) and (Items.userId eq userId) and (Items.itemOrder eq item.order) }) {
            it[difficulty] = item.difficulty.name
            it[originText] = item.originText
            it[keywordsJson] = json(item.keywords)
            it[focusMetricsJson] = json(item.focusMetrics)
            it[focusReason] = item.focusReason
            it[providedFactsJson] = json(item.providedFacts)
            it[requiredIntentsJson] = json(item.requiredIntents)
            it[responseConstraintsJson] = json(item.responseConstraints)
            it[languageComplexityBand] = item.languageComplexityBand
            it[diversityMetadataJson] = item.diversityMetadataJson
            it[updatedAt] = nowUtc
        } == 1
    }

    private fun json(values: List<String>): String = JsonArray(values.map(::JsonPrimitive)).toString()

    private fun toModel(row: ResultRow) = WritingItem(
        row[Items.id], row[Items.dailySetId], row[Items.userId], row[Items.itemOrder],
        jp.co.translacat.languagelearning.features.writing.domain.model.WritingDifficulty.valueOf(
            row[Items.difficulty],
        ),
        row[Items.originText], row[Items.keywordsJson], row[Items.focusMetricsJson], row[Items.focusReason],
        row[Items.providedFactsJson], row[Items.requiredIntentsJson], row[Items.responseConstraintsJson],
        row[Items.languageComplexityBand], row[Items.diversityMetadataJson],
    )
}
