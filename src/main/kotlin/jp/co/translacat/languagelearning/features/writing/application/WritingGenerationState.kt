package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.features.writing.domain.model.*
import kotlinx.serialization.json.*
import java.time.Duration
import java.util.*

/** AI HTTP 호출은 이 유스케이스의 짧은 DB 트랜잭션 밖에서 수행한다. */
internal class WritingGenerationState(private val work: WritingSetUnitOfWork) {
    private val generationLease = Duration.ofMinutes(20) // 기존 BE generation lease

    internal data class GenerationClaim(val order: Int, val token: String)

    suspend fun recoverable(limit: Int = 20): List<WritingGenerationJob> = work.read {
        sets.recoverable(nowUtc, limit)
    }

    suspend fun getOrCreate(seed: NewWritingSet): WritingSet {
        // 기존 snapshot만 반환한다. 같은 날짜/유형의 재요청은 신규 입력으로 덮어쓰지 않는다.
        Json.parseToJsonElement(seed.snapshotJson)
        return work.write(seed.userId) {
            sets.find(seed.userId, seed.learningDate, seed.writingType) ?: sets.create(seed, nowUtc)
        }
    }

    suspend fun claim(userId: Long, setId: Long, token: String = UUID.randomUUID().toString()): String? {
        return claimNext(userId, setId, token)?.token
    }

    suspend fun claimNext(userId: Long, setId: Long, token: String = UUID.randomUUID().toString()): GenerationClaim? {
        require(token.length == 36 && runCatching { UUID.fromString(token) }.isSuccess)
        return work.write(userId) {
            val set = sets.findById(userId, setId) ?: return@write null
            if (set.status != WritingSetStatus.GENERATING) return@write null
            val order = items.firstMissingOrder(userId, setId, set.sentenceCount)
            if (order > set.sentenceCount) {
                sets.markReady(userId, setId, set.generationToken, nowUtc)
                return@write null
            }
            if (sets.claimGeneration(userId, setId, token, nowUtc, nowUtc.plus(generationLease)))
                GenerationClaim(order, token) else null
        }
    }

    /** 검증 완료 문항만 짧은 트랜잭션 안에서 게시한다. 늦은 worker와 중복 순서는 상태를 바꾸지 않는다. */
    suspend fun publishItem(
        userId: Long, setId: Long, claim: GenerationClaim, item: NewWritingItem, promptVersion: String,
    ): Boolean {
        require(promptVersion.length <= 100)
        return work.write(userId) {
            val set = sets.findById(userId, setId) ?: return@write false
            // 재획득 전이라도 만료된 lease의 늦은 응답은 새 문항을 게시할 권한이 없다.
            if (set.status != WritingSetStatus.GENERATING || set.generationToken != claim.token ||
                set.generationLeaseUntil?.isAfter(nowUtc) != true ||
                claim.order != item.order || items.firstMissingOrder(userId, setId, set.sentenceCount) != claim.order
            ) return@write false
            item.validateFor(set.writingType)
            val itemId = items.insert(userId, setId, item, nowUtc)
            val metadata = item.diversityMetadataJson?.let { Json.parseToJsonElement(it).jsonObject }
            if (metadata?.get("contentHash") != null) {
                val snapshot = Json.parseToJsonElement(set.snapshotJson) as? JsonObject
                val learningLanguage = snapshot?.get("learningLanguage")?.jsonPrimitive?.contentOrNull
                    ?: error("WRITING_LEARNING_LANGUAGE_MISSING")
                fingerprints.registerWriting(userId, itemId, learningLanguage, item.originText, metadata, nowUtc)
            }
            check(sets.releaseGeneration(userId, setId, claim.token, promptVersion, nowUtc))
            if (items.firstMissingOrder(userId, setId, set.sentenceCount) > set.sentenceCount)
                check(sets.markReady(userId, setId, null, nowUtc))
            true
        }
    }

    suspend fun retry(userId: Long, setId: Long): WritingSet? = work.write(userId) {
        val existing = sets.findById(userId, setId) ?: return@write null
        if (existing.status == WritingSetStatus.PARTIAL || existing.status == WritingSetStatus.FAILED) {
            if (items.firstMissingOrder(userId, setId, existing.sentenceCount) > existing.sentenceCount)
                sets.markReady(userId, setId, null, nowUtc)
            else sets.restartGeneration(userId, setId, nowUtc)
        }
        sets.findById(userId, setId)
    }

    suspend fun fail(userId: Long, setId: Long, token: String, failureCode: String): Boolean {
        require(Regex("[A-Z][A-Z0-9_]{0,79}").matches(failureCode))
        return work.write(userId) {
            // 만료 작업의 실패도 재시작 가능한 현재 상태를 덮어쓰지 않는다.
            val set = sets.findById(userId, setId) ?: return@write false
            if (set.generationLeaseUntil?.isAfter(nowUtc) != true) return@write false
            sets.failGeneration(userId, setId, token, failureCode, items.count(userId, setId) > 0, nowUtc)
        }
    }

    suspend fun find(userId: Long, setId: Long): WritingSet? = work.read { sets.findById(userId, setId) }
}
