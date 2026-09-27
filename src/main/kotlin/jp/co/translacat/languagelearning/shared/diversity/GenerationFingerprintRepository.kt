package jp.co.translacat.languagelearning.shared.diversity

import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime

internal interface GenerationFingerprintRepository {
    fun register(
        userId: Long, itemId: Long, learningLanguage: String, content: String,
        metadata: JsonObject, nowUtc: LocalDateTime,
    ): Boolean

    fun context(userId: Long, learningLanguage: String, nowUtc: LocalDateTime): JsonObject
}
