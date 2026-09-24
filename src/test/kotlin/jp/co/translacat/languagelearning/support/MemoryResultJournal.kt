package jp.co.translacat.languagelearning.support

import jp.co.translacat.languagelearning.features.resultjournal.application.ResultJournalUnitOfWork
import jp.co.translacat.languagelearning.features.resultjournal.domain.model.IncomingLearningResult
import jp.co.translacat.languagelearning.features.resultjournal.domain.repository.ResultJournalRepository
import java.time.LocalDateTime

/** 순서/롤백 테스트용이다. 실제 DB 잠금의 대체 검증으로 사용하지 않는다. */
internal class MemoryResultJournal : ResultJournalUnitOfWork {
    val events = linkedMapOf<String, IncomingLearningResult>()
    val streams = mutableMapOf<Pair<String, Long>, Long>()
    var failAdvance = false
    var transactions = 0
    override suspend fun <T> execute(userId: Long, block: ResultJournalRepository.() -> T): T {
        transactions++
        val savedEvents = LinkedHashMap(events)
        val savedStreams = HashMap(streams)
        return try {
            block(object : ResultJournalRepository {
                override fun lastSequence(sourceInstanceId: String, userId: Long) =
                    streams[sourceInstanceId to userId] ?: 0L

                override fun findByEventId(eventId: String) = events[eventId]
                override fun append(event: IncomingLearningResult, receivedAt: LocalDateTime) {
                    check(events.putIfAbsent(event.eventId, event) == null)
                }

                override fun advance(sourceInstanceId: String, userId: Long, sequence: Long) {
                    if (failAdvance) error("stream 갱신 실패")
                    streams[sourceInstanceId to userId] = sequence
                }
            })
        } catch (failure: Throwable) {
            events.clear(); events.putAll(savedEvents)
            streams.clear(); streams.putAll(savedStreams)
            throw failure
        }
    }
}
