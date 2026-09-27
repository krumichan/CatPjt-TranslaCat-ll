package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.repository

import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingDiversityValidator
import jp.co.translacat.languagelearning.features.writing.domain.repository.WritingFingerprintRepository
import jp.co.translacat.languagelearning.shared.diversity.ExposedGenerationFingerprintRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime

internal class ExposedWritingFingerprintRepository(private val requireTransaction: () -> Unit) :
    WritingFingerprintRepository {
    private val shared = ExposedGenerationFingerprintRepository(requireTransaction, "WRITING")

    override fun registerWriting(
        userId: Long, itemId: Long, learningLanguage: String, content: String,
        metadata: JsonObject, nowUtc: LocalDateTime,
    ): Boolean {
        // Writing의 기존 hash 검증을 유지한 다음 공통 이력 저장소에 위임한다.
        requireTransaction()
        require(metadata.getValue("contentHash").jsonPrimitive.content == WritingDiversityValidator.hash(content)) {
            "WRITING_FINGERPRINT_HASH_MISMATCH"
        }
        return shared.register(userId, itemId, learningLanguage, content, metadata, nowUtc)
    }

    override fun context(userId: Long, learningLanguage: String, nowUtc: LocalDateTime): JsonObject =
        shared.context(userId, learningLanguage, nowUtc)
}
