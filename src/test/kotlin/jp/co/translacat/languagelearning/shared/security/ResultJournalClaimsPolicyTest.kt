package jp.co.translacat.languagelearning.shared.security

import java.time.Instant
import java.util.*
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ResultJournalClaimsPolicyTest {
    private val now = Instant.parse("2026-09-24T03:00:00Z")
    private val settings =
        InternalApiSettings(enabled = true, secretBase64 = Base64.getEncoder().encodeToString(ByteArray(32)))

    private fun validate(
        scope: List<String>?,
        roles: List<String>? = null,
        subject: String = "translacat-be",
        issued: Instant? = now,
        expires: Instant? = now.plusSeconds(120)
    ) = ResultJournalClaimsPolicy.validate(subject, scope, roles, issued, expires, settings, now)

    @Test
    fun `결과 전용 scope만 수락한다`() {
        assertNotNull(validate(listOf("learning-results:write")))
        for (scope in listOf(
            null,
            emptyList(),
            listOf("settings:read"),
            listOf("learning-results:write", "settings:read"),
            listOf("learning-results:write", "learning-results:write")
        )) assertNull(validate(scope))
    }

    @Test
    fun `사용자 및 관리자 역할로 원장 권한을 대체하지 않는다`() {
        assertNull(validate(listOf("learning-results:write"), roles = listOf("ADMIN")))
        assertNull(validate(listOf("learning-results:write"), subject = "123"))
    }

    @Test
    fun `만료 발급시각 최대 TTL 검증을 유지한다`() {
        assertNull(validate(listOf("learning-results:write"), expires = now.plusSeconds(121)))
        assertNull(validate(listOf("learning-results:write"), issued = null))
        assertNull(
            validate(
                listOf("learning-results:write"), issued = now.minusSeconds(120), expires = now.minusSeconds(6)
            )
        )
        assertNull(
            validate(
                listOf("learning-results:write"), issued = now.plusSeconds(6), expires = now.plusSeconds(120)
            )
        )
        assertNotNull(
            validate(
                listOf("learning-results:write"), issued = now.minusSeconds(120), expires = now.minusSeconds(4)
            )
        )
    }
}
