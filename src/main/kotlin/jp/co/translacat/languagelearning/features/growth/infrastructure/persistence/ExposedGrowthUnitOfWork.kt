package jp.co.translacat.languagelearning.features.growth.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.application.*
import jp.co.translacat.languagelearning.features.growth.domain.model.*
import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.repository.ExposedGrowthRepository
import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.table.*
import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.repository.ExposedLearnerRepository
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal class ExposedGrowthUnitOfWork(
    private val transactions: JdbcTransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
) : GrowthUnitOfWork {
    override suspend fun <T> write(userId: Long, block: GrowthTransaction.() -> T): T = transactions.write {
        scope { guard ->
            ExposedLearnerRepository(guard).ensureAndLock(userId, now()).requireActive()
            block()
        }
    }

    override suspend fun <T> read(block: GrowthTransaction.() -> T): T = transactions.read { scope { _ -> block() } }

    private fun now() = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)

    private fun <T> scope(block: GrowthTransaction.((() -> Unit)) -> T): T {
        val owner = Thread.currentThread()
        val tx = TransactionManager.current()
        var active = true
        val guard = { check(active && Thread.currentThread() === owner && TransactionManager.currentOrNull() === tx) {
            "성장 Repository는 소유 트랜잭션 안에서만 사용할 수 있습니다."
        } }
        val scope = object : GrowthTransaction {
            override val records = ExposedGrowthRepository(guard)
            override fun requireActiveIfPresent(userId: Long) {
                guard()
                val table = jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.table.LearnersTable
                val row = table.selectAll().where { table.userId eq userId }.singleOrNull()
                if (row != null && row[table.status] != "ACTIVE")
                    throw jp.co.translacat.languagelearning.features.learner.domain.exception.LearnerUnavailableException(userId)
            }
            override fun lastSequence(sourceId: String, userId: Long): Long {
                guard()
                return GrowthStreamsTable.selectAll().where {
                    (GrowthStreamsTable.sourceId eq sourceId) and (GrowthStreamsTable.userId eq userId)
                }.singleOrNull()?.get(GrowthStreamsTable.sequence) ?: 0L
            }
            override fun receipt(eventId: String): GrowthReceipt? {
                guard()
                return GrowthReceiptsTable.selectAll().where { GrowthReceiptsTable.eventId eq eventId }.singleOrNull()?.let {
                    GrowthReceipt(it[GrowthReceiptsTable.eventId], it[GrowthReceiptsTable.envelopeHash], it[GrowthReceiptsTable.sequence])
                }
            }
            override fun operationHash(sourceId: String, userId: Long, key: String): String? {
                guard()
                return GrowthOperationsTable.selectAll().where {
                    (GrowthOperationsTable.sourceId eq sourceId) and (GrowthOperationsTable.userId eq userId) and (GrowthOperationsTable.key eq key)
                }.singleOrNull()?.get(GrowthOperationsTable.hash)
            }
            private fun ensureStream(sourceId: String, userId: Long) {
                GrowthStreamsTable.insertIgnore { it[GrowthStreamsTable.sourceId] = sourceId; it[GrowthStreamsTable.userId] = userId; it[sequence] = 0 }
            }
            override fun rememberOperation(sourceId: String, userId: Long, operation: GrowthOperation) {
                guard(); ensureStream(sourceId, userId)
                GrowthOperationsTable.insert { it[GrowthOperationsTable.sourceId] = sourceId; it[GrowthOperationsTable.userId] = userId
                    it[key] = operation.key; it[hash] = operation.hash }
            }
            override fun recordReceipt(event: GrowthEvent) {
                guard(); ensureStream(event.sourceInstanceId, event.userId)
                GrowthReceiptsTable.insert { it[eventId] = event.eventId; it[sourceId] = event.sourceInstanceId; it[userId] = event.userId
                    it[sequence] = event.sequence; it[envelopeHash] = event.envelopeHash; it[occurredAt] = event.occurredAt; it[receivedAt] = now() }
            }
            override fun advance(sourceId: String, userId: Long, sequence: Long) {
                guard()
                check(GrowthStreamsTable.update({ (GrowthStreamsTable.sourceId eq sourceId) and (GrowthStreamsTable.userId eq userId) }) {
                    it[GrowthStreamsTable.sequence] = sequence
                } == 1)
            }
        }
        return try { block(scope, guard) } finally { active = false }
    }
}
