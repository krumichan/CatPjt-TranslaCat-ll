package jp.co.translacat.languagelearning.features.writing.domain.repository

import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime

internal interface WritingFingerprintRepository {
    fun registerWriting(
        userId: Long, itemId: Long, learningLanguage: String, content: String,
        metadata: JsonObject, nowUtc: LocalDateTime,
    ): Boolean

    fun context(userId: Long, learningLanguage: String, nowUtc: LocalDateTime): JsonObject
}
