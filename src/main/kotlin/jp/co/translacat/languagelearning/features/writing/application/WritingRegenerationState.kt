package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.features.writing.domain.model.NewWritingItem
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingDifficulty
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingSetStatus
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingItemRevision
import kotlinx.serialization.json.*
import java.time.Duration
import java.util.UUID
import kotlin.collections.ArrayDeque
import kotlin.collections.List
import kotlin.collections.all
import kotlin.collections.associateBy
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.filterNot
import kotlin.collections.forEach
import kotlin.collections.groupBy
import kotlin.collections.isNotEmpty
import kotlin.collections.map
import kotlin.collections.mapValues
import kotlin.collections.setOf
import kotlin.collections.sortedBy

/** 생성/모델 호출과 별도로 조건부 교체를 관리한다. 호출 중에는 DB 트랜잭션이 없다. */
internal class WritingRegenerationState(private val work: WritingSetUnitOfWork) {
    internal data class Target(
        val itemId: Long, val order: Int, val difficulty: WritingDifficulty, val revision: String,
    )

    internal data class Claim(val userId: Long, val setId: Long, val token: String, val targets: List<Target>)

    suspend fun claim(userId: Long, setId: Long, token: String = UUID.randomUUID().toString()): Claim {
        require(UUID.fromString(token).toString() == token)
        return work.write(userId) {
            val set = sets.findById(userId, setId) ?: error("WRITING_SET_NOT_FOUND")
            require(
                set.status == WritingSetStatus.READY || set.status == WritingSetStatus.COMPLETED,
            ) { "WRITING_SET_GENERATING" }
            require(set.regenerationCount < 3) { "WRITING_REGENERATION_LIMIT" }
            val targets = items.list(userId, setId).filterNot { items.hasAnswer(userId, it.id) }
                .map { Target(it.id, it.order, it.difficulty, WritingItemRevision.of(it)) }
            require(targets.isNotEmpty()) { "WRITING_NO_UNANSWERED_ITEM" }
            check(sets.claimRegeneration(userId, setId, token, nowUtc, nowUtc.plus(Duration.ofMinutes(10)))) {
                "WRITING_REGENERATION_IN_PROGRESS"
            }
            Claim(userId, setId, token, targets)
        }
    }

    suspend fun publish(claim: Claim, replacements: List<NewWritingItem>): Boolean = work.write(claim.userId) {
        val set = sets.findById(claim.userId, claim.setId) ?: return@write false
        // 토큰이 아직 같아도 만료된 생성 결과는 원래 문항·답변·횟수를 바꾸지 않는다.
        if (set.generationToken != claim.token || set.generationLeaseUntil?.isAfter(nowUtc) != true || set.status !in setOf(
                WritingSetStatus.READY, WritingSetStatus.COMPLETED,
            )
        )
            return@write false
        require(replacements.size == claim.targets.size) { "WRITING_REGENERATION_COUNT" }
        val available =
            replacements.groupBy { it.difficulty }.mapValues { (_, values) -> ArrayDeque(values.sortedBy { it.order }) }
        val current = items.list(claim.userId, claim.setId).associateBy { it.id }
        val updates = claim.targets.sortedBy { it.order }.map { target ->
            val old = current[target.itemId]
            check(
                old != null && old.order == target.order && old.difficulty == target.difficulty &&
                    WritingItemRevision.of(old) == target.revision && !items.hasAnswer(claim.userId, target.itemId),
            ) {
                "WRITING_REGENERATION_CONFLICT"
            }
            val next = available[target.difficulty]?.removeFirstOrNull() ?: error("WRITING_REGENERATION_DISTRIBUTION")
            val indexed = next.copy(order = target.order)
            indexed.validateFor(set.writingType)
            target.itemId to indexed
        }
        require(available.values.all { it.isEmpty() }) { "WRITING_REGENERATION_DISTRIBUTION" }
        updates.forEach { (itemId, replacement) ->
            check(items.replace(claim.userId, itemId, replacement, nowUtc))
            val metadata = replacement.diversityMetadataJson?.let { Json.parseToJsonElement(it).jsonObject }
            if (metadata?.get("contentHash") != null) {
                val snapshot = Json.parseToJsonElement(set.snapshotJson) as? JsonObject
                val learningLanguage = snapshot?.get("learningLanguage")?.jsonPrimitive?.contentOrNull
                    ?: error("WRITING_LEARNING_LANGUAGE_MISSING")
                fingerprints.registerWriting(
                    claim.userId, itemId, learningLanguage,
                    replacement.originText, metadata, nowUtc,
                )
            }
        }
        check(sets.finishRegeneration(claim.userId, claim.setId, claim.token, true, nowUtc))
        true
    }

    suspend fun release(claim: Claim): Boolean = work.write(claim.userId) {
        sets.finishRegeneration(claim.userId, claim.setId, claim.token, false, nowUtc)
    }
}
