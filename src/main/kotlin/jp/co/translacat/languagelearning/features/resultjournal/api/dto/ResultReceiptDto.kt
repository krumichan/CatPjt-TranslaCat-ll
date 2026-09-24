package jp.co.translacat.languagelearning.features.resultjournal.api.dto

import kotlinx.serialization.Serializable

/** 원장 저장 완료 응답이다. Profile 집계 완료를 뜻하지 않는다. */
@Serializable
internal data class ResultReceiptDto(
    val sourceInstanceId: String,
    val eventId: String,
    val userId: Long,
    val sequence: Long,
    val payloadSha256: String,
    val outcome: String,
)
