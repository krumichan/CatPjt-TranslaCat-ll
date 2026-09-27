package jp.co.translacat.languagelearning.features.writing.domain.repository

import jp.co.translacat.languagelearning.features.writing.domain.model.NewWritingSet
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingGenerationJob
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingSet
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import java.time.LocalDate
import java.time.LocalDateTime

internal interface WritingSetRepository {
    fun existsForUser(userId: Long): Boolean
    fun recoverable(nowUtc: LocalDateTime, limit: Int): List<WritingGenerationJob>
    fun find(userId: Long, date: LocalDate, type: WritingType): WritingSet?
    fun findById(userId: Long, setId: Long): WritingSet?
    fun create(value: NewWritingSet, nowUtc: LocalDateTime): WritingSet
    fun claimGeneration(
        userId: Long, setId: Long, token: String, nowUtc: LocalDateTime, leaseUntil: LocalDateTime,
    ): Boolean

    fun releaseGeneration(
        userId: Long, setId: Long, token: String, promptVersion: String, nowUtc: LocalDateTime,
    ): Boolean

    fun failGeneration(
        userId: Long, setId: Long, token: String, message: String, hasItems: Boolean, nowUtc: LocalDateTime,
    ): Boolean

    fun restartGeneration(userId: Long, setId: Long, nowUtc: LocalDateTime): Boolean
    fun markReady(userId: Long, setId: Long, token: String?, nowUtc: LocalDateTime): Boolean
    fun claimRegeneration(
        userId: Long, setId: Long, token: String, nowUtc: LocalDateTime, leaseUntil: LocalDateTime,
    ): Boolean

    fun finishRegeneration(userId: Long, setId: Long, token: String, increment: Boolean, nowUtc: LocalDateTime): Boolean
    fun complete(userId: Long, setId: Long, nowUtc: LocalDateTime): Boolean
}
