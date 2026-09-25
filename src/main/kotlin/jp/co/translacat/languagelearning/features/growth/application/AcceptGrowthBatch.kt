package jp.co.translacat.languagelearning.features.growth.application

import jp.co.translacat.languagelearning.features.growth.domain.exception.GrowthConflict
import jp.co.translacat.languagelearning.features.growth.domain.model.*

internal class AcceptGrowthBatch(private val work: GrowthUnitOfWork, private val sourceId: String) {
    suspend fun execute(event: GrowthEvent): GrowthAcknowledgement {
        if (event.sourceInstanceId != sourceId) throw GrowthConflict("GROWTH_SOURCE_MISMATCH")
        require(event.userId > 0 && event.sequence > 0 && event.operations.isNotEmpty())
        require(event.operations.map { it.key }.distinct().size == event.operations.size)
        return work.write(event.userId) {
            val last = lastSequence(sourceId, event.userId)
            val prior = receipt(event.eventId)
            if (prior != null) {
                if (prior.envelopeHash != event.envelopeHash || prior.sequence != event.sequence || last < event.sequence)
                    throw GrowthConflict("GROWTH_EVENT_CONFLICT")
                acknowledgement(event, "DUPLICATE")
            } else {
                if (last == Long.MAX_VALUE || event.sequence != last + 1) throw GrowthConflict("GROWTH_SEQUENCE_CONFLICT")
                val projector = GrowthProjector(records)
                event.operations.forEach { operation ->
                    val old = operationHash(sourceId, event.userId, operation.key)
                    if (old != null && old != operation.hash) throw GrowthConflict("GROWTH_OPERATION_CONFLICT")
                    if (old == null) {
                        projector.apply(event.userId, operation.change, event.eventTime)
                        rememberOperation(sourceId, event.userId, operation)
                    }
                }
                recordReceipt(event)
                advance(sourceId, event.userId, event.sequence)
                acknowledgement(event, "APPLIED")
            }
        }
    }

    private fun acknowledgement(event: GrowthEvent, outcome: String) = GrowthAcknowledgement(
        sourceId, event.eventId, event.userId, event.sequence, event.payloadSha256, outcome,
    )
}
