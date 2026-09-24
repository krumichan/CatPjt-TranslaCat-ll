package jp.co.translacat.languagelearning.features.resultjournal.api.dto

import jp.co.translacat.languagelearning.features.resultjournal.domain.model.IncomingLearningResult
import jp.co.translacat.languagelearning.features.resultjournal.domain.model.ResultKind
import kotlinx.serialization.Serializable

@Serializable
internal data class ResultEnvelopeDto(
    val schemaVersion: Int,
    val sourceInstanceId: String,
    val eventId: String,
    val userId: Long,
    val sequence: Long,
    val kind: String,
    val referenceId: String,
    val occurredAt: String,
    val payloadJson: String,
    val payloadSha256: String,
) {
    fun toDomain() = IncomingLearningResult(schemaVersion, sourceInstanceId, eventId, userId, sequence,
        ResultKind.valueOf(kind), referenceId, occurredAt, payloadJson, payloadSha256)
    override fun toString(): String = "ResultEnvelopeDto(eventId=$eventId, payload=<redacted>)"
}
