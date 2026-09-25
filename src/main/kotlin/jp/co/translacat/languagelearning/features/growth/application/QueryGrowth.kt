package jp.co.translacat.languagelearning.features.growth.application

import jp.co.translacat.languagelearning.features.growth.domain.exception.*
import jp.co.translacat.languagelearning.features.growth.domain.model.*
import jp.co.translacat.languagelearning.features.growth.domain.policy.GrowthPolicy
import java.time.*

/** 커밋된 watermark보다 뒤처진 값을 최신이라고 반환하지 않는다. preview에는 저장 부작용이 없다. */
internal class QueryGrowth(private val work: GrowthUnitOfWork, private val expectedSource: String, private val clock: Clock = Clock.systemUTC()) {
    suspend fun snapshot(userId: Long, sourceId: String, minimum: Long, operations: List<GrowthOperation>, keys: List<String>?): GrowthSnapshot {
        request(userId, sourceId, minimum)
        require(operations.size <= 256 && operations.map { it.key }.distinct().size == operations.size)
        require(keys == null || keys.size <= 500)
        return work.read {
            requireActiveIfPresent(userId)
            val sequence = lastSequence(sourceId, userId)
            if (sequence < minimum) throw GrowthPending(minimum, sequence)
            val view = GrowthPreview(records)
            val projector = GrowthProjector(view)
            val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
            operations.forEach { operation ->
                val previous = operationHash(sourceId, userId, operation.key)
                if (previous != null && previous != operation.hash) throw GrowthConflict("GROWTH_OPERATION_CONFLICT")
                if (previous == null) projector.apply(userId, operation.change, now)
            }
            GrowthSnapshot(userId, sourceId, sequence, operations.isNotEmpty(), view.profile(userId),
                view.masteries(userId, keys, if (keys == null) 100 else 500),
                GrowthPolicy.signalTypes.associateWith { view.signals(userId, it, 100) })
        }
    }
    suspend fun activities(userId: Long, sourceId: String, minimum: Long, source: String?, from: LocalDate, to: LocalDate, after: Long): GrowthActivityPage {
        request(userId, sourceId, minimum)
        require(source == null || source in GrowthPolicy.sources)
        require(!to.isBefore(from) && after >= 0)
        return work.read {
            requireActiveIfPresent(userId)
            val sequence = lastSequence(sourceId, userId)
            if (sequence < minimum) throw GrowthPending(minimum, sequence)
            val values = records.activities(userId, source, from, to, after, 26)
            val page = values.take(25)
            GrowthActivityPage(userId, sourceId, sequence, page.map { it to records.metrics(it.id) }, if (values.size > 25) page.last().id else null, "$sequence:${records.profile(userId)?.baselineCompletionId ?: "none"}")
        }
    }
    private fun request(userId: Long, sourceId: String, minimum: Long) {
        require(userId > 0 && minimum >= 0)
        if (sourceId != expectedSource) throw GrowthConflict("GROWTH_SOURCE_MISMATCH")
    }
}
