package jp.co.translacat.languagelearning.features.resultjournal.infrastructure.persistence.repository

import jp.co.translacat.languagelearning.features.resultjournal.domain.model.IncomingLearningResult
import jp.co.translacat.languagelearning.features.resultjournal.domain.model.ResultKind
import jp.co.translacat.languagelearning.features.resultjournal.domain.repository.ResultJournalRepository
import jp.co.translacat.languagelearning.features.resultjournal.infrastructure.persistence.table.ResultEventsTable as Events
import jp.co.translacat.languagelearning.features.resultjournal.infrastructure.persistence.table.ResultStreamsTable as Streams
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.LocalDateTime

internal class ExposedResultJournalRepository(private val requireTransaction: () -> Unit) : ResultJournalRepository {
    override fun lastSequence(sourceInstanceId: String, userId: Long): Long {
        requireTransaction()
        Streams.upsert(onUpdate = { it[Streams.userId] = userId }) {
            it[Streams.sourceInstanceId] = sourceInstanceId
            it[Streams.userId] = userId
            it[Streams.lastSequence] = 0
        }
        return Streams.selectAll().where { (Streams.sourceInstanceId eq sourceInstanceId) and (Streams.userId eq userId) }
            .forUpdate().single()[Streams.lastSequence]
    }
    override fun findByEventId(eventId: String): IncomingLearningResult? {
        requireTransaction()
        return Events.selectAll().where { Events.eventId eq eventId }.forUpdate().singleOrNull()?.let { row ->
            IncomingLearningResult(row[Events.schemaVersion], row[Events.sourceInstanceId], row[Events.eventId],
                row[Events.userId], row[Events.sequence], ResultKind.valueOf(row[Events.kind]), row[Events.referenceId],
                row[Events.occurredAt], row[Events.payloadJson], row[Events.payloadSha256])
        }
    }
    override fun append(event: IncomingLearningResult, receivedAt: LocalDateTime) {
        requireTransaction()
        Events.insert {
            it[eventId] = event.eventId
            it[schemaVersion] = event.schemaVersion
            it[sourceInstanceId] = event.sourceInstanceId
            it[userId] = event.userId
            it[sequence] = event.sequence
            it[kind] = event.kind.name
            it[referenceId] = event.referenceId
            it[occurredAt] = event.occurredAt
            it[payloadJson] = event.payloadJson
            it[payloadSha256] = event.payloadSha256
            it[aggregationEligible] = event.kind.aggregationEligible
            it[Events.receivedAt] = receivedAt
        }
    }
    override fun advance(sourceInstanceId: String, userId: Long, sequence: Long) {
        requireTransaction()
        check(Streams.update({ (Streams.sourceInstanceId eq sourceInstanceId) and (Streams.userId eq userId) }) {
            it[lastSequence] = sequence
        } == 1) { "결과 원장 stream이 없습니다." }
    }
}
