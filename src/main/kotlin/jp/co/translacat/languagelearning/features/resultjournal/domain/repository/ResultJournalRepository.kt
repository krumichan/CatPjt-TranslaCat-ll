package jp.co.translacat.languagelearning.features.resultjournal.domain.repository

import jp.co.translacat.languagelearning.features.resultjournal.domain.model.IncomingLearningResult
import java.time.LocalDateTime

internal interface ResultJournalRepository {
    fun lastSequence(sourceInstanceId: String, userId: Long): Long
    fun findByEventId(eventId: String): IncomingLearningResult?
    fun append(event: IncomingLearningResult, receivedAt: LocalDateTime)
    fun advance(sourceInstanceId: String, userId: Long, sequence: Long)
}
