package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionResult
import kotlinx.serialization.json.JsonObject

/** 생성 호출 전후의 영속 상태 경계다. 트랜잭션 구현은 infrastructure가 제공한다. */
internal interface CuratedWritingGenerationPersistence {
    data class Claim(val id: String, val status: String, val execute: Boolean, val input: JsonObject)

    suspend fun prepare(user: Long, key: String, type: WritingType, band: Int, topic: String,
                        repairOf: String? = null, repairNotes: String? = null): Claim
    suspend fun saveOutput(user: Long, key: String, result: ModelExecutionResult)
    suspend fun finish(user: Long, key: String): JsonObject
    suspend fun fail(user: Long, key: String, code: String, uncertain: Boolean)
    suspend fun get(user: Long, key: String): JsonObject
}
