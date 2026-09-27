package jp.co.translacat.languagelearning.features.writing.domain.repository

import jp.co.translacat.languagelearning.features.writing.domain.model.NewWritingItem
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingItem
import java.time.LocalDateTime

internal interface WritingItemRepository {
    fun firstMissingOrder(userId: Long, setId: Long, sentenceCount: Int): Int
    fun count(userId: Long, setId: Long): Int
    fun insert(userId: Long, setId: Long, item: NewWritingItem, nowUtc: LocalDateTime): Long
    fun list(userId: Long, setId: Long): List<WritingItem>
    fun find(userId: Long, itemId: Long): WritingItem?
    fun hasAnswer(userId: Long, itemId: Long): Boolean
    fun replace(userId: Long, itemId: Long, item: NewWritingItem, nowUtc: LocalDateTime): Boolean
}
