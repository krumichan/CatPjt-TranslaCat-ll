package jp.co.translacat.languagelearning.shared.security

import java.time.Instant
import java.util.*
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GrowthClaimsPolicyTest {
    private val now = Instant.parse("2026-09-25T10:00:00Z")
    private val settings = InternalApiSettings(
        enabled = true, secretBase64 = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
    )

    private fun valid(
        subject: String? = settings.callerService, scopes: List<String>? = listOf("growth:write"),
        roles: List<String>? = null,
        issued: Instant? = now, expires: Instant? = now.plusSeconds(60), config: InternalApiSettings = settings,
    ) = GrowthClaimsPolicy.validate(subject, scopes, roles, issued, expires, config, now)

    @Test
    fun `성장 전용 scope와 호출 서비스만 허용한다`() {
        assertNotNull(valid()); assertNull(valid(subject = "123")); assertNull(valid(scopes = listOf("settings:read")))
        assertNull(valid(scopes = listOf("growth:write", "settings:read"))); assertNull(
            valid(scopes = listOf("growth:write", "growth:write")),
        ); assertNull(valid(scopes = null))
    }

    @Test
    fun `사용자 관리자 role과 서비스 권한을 혼합하지 않는다`() {
        assertNull(valid(roles = listOf("ADMIN"))); assertNull(valid(roles = listOf("USER"))); assertNotNull(
            valid(roles = emptyList()),
        )
    }

    @Test
    fun `만료 미래 발급 누락 및 과도한 TTL을 거부한다`() {
        assertNull(valid(issued = null)); assertNull(valid(expires = null)); assertNull(
            valid(expires = now),
        ); assertNull(valid(expires = now.plusSeconds(121)))
        assertNull(valid(issued = now.plusSeconds(6))); assertNull(
            valid(issued = now.minusSeconds(60), expires = now.minusSeconds(6)),
        )
    }

    @Test
    fun `내부 인증이 꺼져 있으면 서명 전 단계에서도 거부한다`() {
        assertNull(valid(config = InternalApiSettings()))
    }
}
