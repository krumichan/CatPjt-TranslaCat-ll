package jp.co.translacat.languagelearning.shared.security

import java.time.Instant
import java.util.*
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InternalServiceClaimsPolicyTest {
    private val settings = InternalApiSettings(
        enabled = true, secretBase64 = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
    )
    private val now = Instant.parse("2026-09-24T01:00:00Z")
    private val valid = InternalServiceClaims("translacat-be", listOf("settings:read"), null, now, now.plusSeconds(60))

    @Test
    fun `전용 subject와 read scope만 수락한다`() {
        assertNotNull(InternalServiceClaimsPolicy.validate(valid, settings, now))
    }

    @Test
    fun `사용자 subject나 관리자 roles가 섞이면 거부한다`() {
        assertNull(InternalServiceClaimsPolicy.validate(valid.copy(subject = "123"), settings, now))
        assertNull(InternalServiceClaimsPolicy.validate(valid.copy(roles = listOf("ADMIN")), settings, now))
    }

    @Test
    fun `누락 중복 확장 scope는 거부한다`() {
        for (scopes in listOf(null, emptyList(), listOf("settings:write"), listOf("settings:read", "settings:read"))) {
            assertNull(InternalServiceClaimsPolicy.validate(valid.copy(scopes = scopes), settings, now))
        }
    }

    @Test
    fun `만료 미래 발급 과도한 TTL은 거부한다`() {
        assertNull(
            InternalServiceClaimsPolicy.validate(
                valid.copy(
                    issuedAt = now.minusSeconds(200), expiresAt = now.minusSeconds(140),
                ),
                settings, now,
            ),
        )
        assertNull(
            InternalServiceClaimsPolicy.validate(
                valid.copy(
                    issuedAt = now.plusSeconds(6), expiresAt = now.plusSeconds(66),
                ),
                settings, now,
            ),
        )
        assertNull(InternalServiceClaimsPolicy.validate(valid.copy(expiresAt = now.plusSeconds(121)), settings, now))
    }

    @Test
    fun `비활성 설정과 필수 시간 누락은 거부한다`() {
        assertNull(InternalServiceClaimsPolicy.validate(valid, InternalApiSettings(), now))
        assertNull(InternalServiceClaimsPolicy.validate(valid.copy(issuedAt = null), settings, now))
        assertNull(InternalServiceClaimsPolicy.validate(valid.copy(expiresAt = null), settings, now))
    }

    @Test
    fun `clock skew는 설정된 범위까지만 허용한다`() {
        assertNotNull(
            InternalServiceClaimsPolicy.validate(
                valid.copy(
                    issuedAt = now.minusSeconds(60), expiresAt = now.minusSeconds(4),
                ),
                settings, now,
            ),
        )
        assertNull(
            InternalServiceClaimsPolicy.validate(
                valid.copy(
                    issuedAt = now.minusSeconds(60), expiresAt = now.minusSeconds(6),
                ),
                settings, now,
            ),
        )
    }
}
