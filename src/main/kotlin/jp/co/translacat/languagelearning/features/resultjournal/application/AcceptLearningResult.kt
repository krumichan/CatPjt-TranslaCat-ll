package jp.co.translacat.languagelearning.features.resultjournal.application

import jp.co.translacat.languagelearning.features.resultjournal.domain.exception.ResultJournalConflict
import jp.co.translacat.languagelearning.features.resultjournal.domain.model.IncomingLearningResult
import jp.co.translacat.languagelearning.features.resultjournal.domain.model.ReceiptOutcome
import jp.co.translacat.languagelearning.features.resultjournal.domain.model.ResultReceipt
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal class AcceptLearningResult(
    private val unitOfWork: ResultJournalUnitOfWork,
    private val sourceInstanceId: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    init { IncomingLearningResult.requireUuid(sourceInstanceId) }

    suspend fun execute(event: IncomingLearningResult): ResultReceipt {
        event.validate()
        if (event.sourceInstanceId != sourceInstanceId) throw ResultJournalConflict("RESULT_SOURCE_MISMATCH")
        return unitOfWork.execute(event.userId) journal@ {
            val last = lastSequence(event.sourceInstanceId, event.userId)
            val previous = findByEventId(event.eventId)
            if (previous != null) {
                // 동일 eventId라도 source/사용자/순번/종류/시각/본문이 다르면 성공으로 숨기지 않는다.
                if (previous != event) throw ResultJournalConflict("RESULT_EVENT_CONFLICT")
                if (last < event.sequence) throw ResultJournalConflict("RESULT_LEDGER_INCONSISTENT")
                return@journal receipt(event, ReceiptOutcome.DUPLICATE)
            }
            if (last == Long.MAX_VALUE || event.sequence != last + 1) {
                throw ResultJournalConflict("RESULT_SEQUENCE_GAP_OR_CONFLICT")
            }
            val receivedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
            append(event, receivedAt)
            advance(event.sourceInstanceId, event.userId, event.sequence)
            receipt(event, ReceiptOutcome.RECORDED)
        }
    }

    private fun receipt(event: IncomingLearningResult, outcome: ReceiptOutcome) = ResultReceipt(
        event.sourceInstanceId, event.eventId, event.userId, event.sequence, event.payloadSha256, outcome,
    )
}
