package jp.co.translacat.languagelearning.support

import jp.co.translacat.languagelearning.features.growth.application.GrowthTransaction
import jp.co.translacat.languagelearning.features.growth.application.GrowthUnitOfWork
import jp.co.translacat.languagelearning.features.growth.domain.model.*
import jp.co.translacat.languagelearning.features.growth.domain.repository.GrowthRepository
import jp.co.translacat.languagelearning.features.learner.domain.exception.LearnerUnavailableException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/** 저장소만 대체한다. projector와 수신/중복 정책은 실제 코드를 실행한다. */
internal class MemoryGrowthUnitOfWork : GrowthUnitOfWork {
    class State : GrowthRepository {
        val profiles = linkedMapOf<Long, GrowthProfile>()
        val masteries = linkedMapOf<Pair<Long, String>, KeywordMastery>()
        val signals = linkedMapOf<Triple<Long, String, String>, GrowthSignal>()
        val evidence = linkedMapOf<List<Any>, GrowthEvidence>()
        val activities = linkedMapOf<Triple<Long, String, String>, GrowthActivity>()
        val metricRows = linkedMapOf<Long, List<GrowthMetric>>()
        val sequences = linkedMapOf<Pair<String, Long>, Long>()
        val receipts = linkedMapOf<String, GrowthReceipt>()
        val operationHashes = linkedMapOf<List<Any>, String>()
        val inactive = mutableSetOf<Long>()
        var nextId = 1L
        fun copy(): State = State().also {
            it.profiles.putAll(profiles); it.masteries.putAll(masteries); it.signals.putAll(signals)
            it.evidence.putAll(evidence); it.activities.putAll(activities); it.metricRows.putAll(metricRows)
            it.sequences.putAll(sequences); it.receipts.putAll(receipts); it.operationHashes.putAll(operationHashes)
            it.inactive.addAll(inactive); it.nextId = nextId
        }

        override fun profile(userId: Long) = profiles[userId]
        override fun saveProfile(value: GrowthProfile) {
            profiles[value.userId] = value
        }

        override fun mastery(userId: Long, key: String) = masteries[userId to key]
        override fun saveMastery(value: KeywordMastery) {
            masteries[value.userId to value.canonicalKey] = value
        }

        override fun masteries(userId: Long, keys: List<String>?, limit: Int) =
            masteries.values.filter { it.userId == userId && (keys == null || it.canonicalKey in keys) }
                .sortedBy { it.score }
                .take(limit)

        override fun signal(userId: Long, type: String, key: String) = signals[Triple(userId, type, key)]
        override fun saveSignal(value: GrowthSignal) {
            signals[Triple(value.userId, value.type, value.key)] = value
        }

        override fun signals(userId: Long, type: String, limit: Int) =
            signals.values.filter { it.userId == userId && it.type == type }
                .sortedByDescending { it.occurrenceCount }
                .take(limit)

        override fun evidence(userId: Long, source: String, pattern: String, direction: String) =
            evidence[listOf(userId, source, pattern, direction)]

        override fun saveEvidence(value: GrowthEvidence) {
            evidence[listOf(value.userId, value.source, value.patternKey, value.direction)] = value
        }

        override fun evidenceList(userId: Long, source: String?, limit: Int) =
            evidence.values.filter { it.userId == userId && (source == null || source == it.source) }.take(limit)

        override fun activity(userId: Long, source: String, referenceId: String) =
            activities[Triple(userId, source, referenceId)]

        override fun saveActivity(value: GrowthActivity): GrowthActivity {
            val result = value.copy(id = if (value.id == 0L) nextId++ else value.id)
            activities[Triple(value.userId, value.source, value.referenceId)] = result; return result
        }

        override fun activities(
            userId: Long, source: String?, from: LocalDate, to: LocalDate, afterId: Long, limit: Int,
        ) =
            activities.values.filter { it.userId == userId && (source == null || source == it.source) && it.learningDate in from..to && it.id > afterId }
                .sortedBy { it.id }
                .take(limit)

        override fun metrics(activityId: Long) = metricRows[activityId].orEmpty()
        override fun replaceMetrics(activityId: Long, metrics: List<GrowthMetric>) {
            require(metrics.map { it.metricType }.distinct().size == metrics.size); metricRows[activityId] =
                metrics.toList()
        }
    }

    var state = State(); private set
    private val lock = Mutex()
    private fun scope(s: State) = object : GrowthTransaction {
        override val records: GrowthRepository = s
        override fun requireActiveIfPresent(userId: Long) {
            if (userId in s.inactive) throw LearnerUnavailableException(userId)
        }

        override fun lastSequence(sourceId: String, userId: Long) = s.sequences[sourceId to userId] ?: 0L
        override fun receipt(eventId: String) = s.receipts[eventId]
        override fun operationHash(sourceId: String, userId: Long, key: String) =
            s.operationHashes[listOf(sourceId, userId, key)]

        override fun rememberOperation(sourceId: String, userId: Long, operation: GrowthOperation) {
            s.operationHashes[listOf(sourceId, userId, operation.key)] = operation.hash
        }

        override fun recordReceipt(event: GrowthEvent) {
            check(event.eventId !in s.receipts); s.receipts[event.eventId] =
                GrowthReceipt(event.eventId, event.envelopeHash, event.sequence)
        }

        override fun advance(sourceId: String, userId: Long, sequence: Long) {
            s.sequences[sourceId to userId] = sequence
        }
    }

    override suspend fun <T> write(userId: Long, block: GrowthTransaction.() -> T): T = lock.withLock {
        val snapshot = state.copy();
        val tx = scope(snapshot); tx.requireActiveIfPresent(userId)
        val result = tx.block(); state = snapshot; result
    }

    override suspend fun <T> read(block: GrowthTransaction.() -> T): T = lock.withLock { scope(state.copy()).block() }
}
