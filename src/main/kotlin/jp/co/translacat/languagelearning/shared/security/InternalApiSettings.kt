package jp.co.translacat.languagelearning.shared.security

import java.util.*

/** 외부 로그인 JWT와 다른 키/발급자/수신자를 사용한다. 비밀키는 toString에 포함하지 않는다. */
internal class InternalApiSettings(
    val enabled: Boolean = false,
    val issuer: String = "translacat-be",
    val audience: String = "translacat-ll",
    val callerService: String = "translacat-be",
    val maxTtlSeconds: Long = 120,
    val clockSkewSeconds: Long = 5,
    secretBase64: String = "",
) {
    private val key: ByteArray = if (enabled) {
        require(issuer.isNotBlank() && audience.isNotBlank() && callerService.isNotBlank()) { "내부 JWT 대상 설정이 필요합니다." }
        require(maxTtlSeconds in 15..300 && clockSkewSeconds in 0..10) { "내부 JWT 유효기간 설정이 올바르지 않습니다." }
        val decoded = try {
            Base64.getDecoder().decode(secretBase64)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("내부 JWT 비밀키는 Base64로 인코딩된 무작위 키여야 합니다.")
        }
        require(decoded.size in 32..128) { "내부 JWT 키는 32~128바이트여야 합니다. 실제 키 값은 로그에 남기지 마세요." }
        decoded
    } else byteArrayOf()

    fun verificationKey(): ByteArray {
        check(enabled) { "내부 API가 비활성화되어 있습니다." }
        return key.copyOf()
    }

    override fun toString(): String = "InternalApiSettings(enabled=$enabled, credentials=<redacted>)"
}
