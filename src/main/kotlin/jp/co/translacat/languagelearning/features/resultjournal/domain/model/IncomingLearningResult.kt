package jp.co.translacat.languagelearning.features.resultjournal.domain.model

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** 검증된 결과의 불변 전달본이다. 이것을 Profile/Activity 최신 상태로 반환하지 않는다. */
internal data class IncomingLearningResult(
    val schemaVersion: Int,
    val sourceInstanceId: String,
    val eventId: String,
    val userId: Long,
    val sequence: Long,
    val kind: ResultKind,
    val referenceId: String,
    val occurredAt: String,
    val payloadJson: String,
    val payloadSha256: String,
) {
    fun validate() {
        require(schemaVersion == 1) { "지원하지 않는 결과 원장 버전입니다." }
        requireUuid(sourceInstanceId)
        requireUuid(eventId)
        require(userId > 0 && sequence > 0) { "사용자 ID와 순번은 양수여야 합니다." }
        require(Regex("[1-9][0-9]{0,18}").matches(referenceId)) { "결과 참조 ID를 확인해 주세요." }
        require(referenceId.toLongOrNull() != null) { "결과 참조 ID 범위를 확인해 주세요." }
        val at = Instant.parse(occurredAt)
        require(at.nano % 1_000 == 0) { "결과 시각은 마이크로초 정밀도 이하여야 합니다." }
        require(payloadSha256.matches(Regex("[0-9a-f]{64}"))) { "본문 해시 형식을 확인해 주세요." }
        require(payloadSha256 == hash(payloadJson)) { "본문과 해시가 일치하지 않습니다." }
    }
    override fun toString(): String = "IncomingLearningResult(eventId=$eventId, sequence=$sequence, payload=<redacted>)"
    companion object {
        const val MAX_PAYLOAD_BYTES = 262_144
        fun hash(payload: String): String {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            require(bytes.isNotEmpty() && bytes.size <= MAX_PAYLOAD_BYTES) { "결과 본문의 크기를 확인해 주세요." }
            return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
        fun requireUuid(value: String) {
            require(value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) {
                "source 또는 event UUID 형식을 확인해 주세요."
            }
            UUID.fromString(value)
        }
    }
}

internal enum class ResultKind(val aggregationEligible: Boolean) {
    WRITING_SCORED(true),
    SPEAKING_SCORED(true),
    SPEAKING_INSUFFICIENT(false),
}
