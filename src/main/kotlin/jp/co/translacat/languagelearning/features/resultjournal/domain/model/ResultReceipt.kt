package jp.co.translacat.languagelearning.features.resultjournal.domain.model

/** RECORDED는 원장 저장 성공이지 Profile/Activity 반영 완료가 아니다. */
internal data class ResultReceipt(
    val sourceInstanceId: String,
    val eventId: String,
    val userId: Long,
    val sequence: Long,
    val payloadSha256: String,
    val outcome: ReceiptOutcome,
)

internal enum class ReceiptOutcome { RECORDED, DUPLICATE }
