package jp.co.translacat.languagelearning.shared.security

import java.time.Instant
import java.util.Base64
import kotlin.test.*

class InternalClaimsPolicyTest {
    private val settings=InternalApiSettings(enabled=true,secretBase64=Base64.getEncoder().encodeToString(ByteArray(32){it.toByte()}))
    private val now=Instant.parse("2026-09-24T03:30:00Z")
    private val valid=InternalClaims("123",listOf("USER"),now,now.plusSeconds(60))
    @Test fun `비활성 내부 API의 클레임은 수락하지 않는다`() {
        assertNull(InternalClaimsPolicy.validate(valid, InternalApiSettings(), now))
    }
    @Test fun `정상 단기 토큰은 사용자 식별자를 반환한다`() {
        assertEquals(123L,InternalClaimsPolicy.validate(valid,settings,now)?.userId)
        assertFalse(InternalClaimsPolicy.validate(valid,settings,now)!!.administrator)
    }
    @Test fun `ADMIN은 명시된 경우에만 관리자다`() {
        assertTrue(InternalClaimsPolicy.validate(valid.copy(roles=listOf("USER","ADMIN")),settings,now)!!.administrator)
    }
    @Test fun `잘못된 식별자와 overflow는 거부한다`() {
        for (id in listOf(null,"", "0","-1","+1","01"," 1","1.0","9223372036854775808","x")) {
            assertNull(InternalClaimsPolicy.validate(valid.copy(subject=id),settings,now))
        }
    }
    @Test fun `Long 최대 식별자는 정확하게 유지한다`() {
        assertEquals(Long.MAX_VALUE,InternalClaimsPolicy.validate(valid.copy(subject=Long.MAX_VALUE.toString()),settings,now)?.userId)
    }
    @Test fun `미등록 역할과 중복 또는 누락은 거부한다`() {
        for(roles in listOf(null,emptyList(),listOf("admin"),listOf("ROOT"),listOf("ADMIN","ADMIN"))) {
            assertNull(InternalClaimsPolicy.validate(valid.copy(roles=roles),settings,now))
        }
    }
    @Test fun `만료 또는 필수 시간 누락은 거부한다`() {
        assertNull(InternalClaimsPolicy.validate(valid.copy(issuedAt=null),settings,now))
        assertNull(InternalClaimsPolicy.validate(valid.copy(expiresAt=null),settings,now))
        assertNull(InternalClaimsPolicy.validate(valid.copy(issuedAt=now.minusSeconds(70),expiresAt=now.minusSeconds(10)),settings,now))
    }
    @Test fun `너무 긴 유효기간과 미래 발급은 거부한다`() {
        assertNull(InternalClaimsPolicy.validate(valid.copy(expiresAt=now.plusSeconds(121)),settings,now))
        assertNull(InternalClaimsPolicy.validate(valid.copy(issuedAt=now.plusSeconds(6)),settings,now))
        assertNull(InternalClaimsPolicy.validate(valid.copy(expiresAt=now),settings,now))
    }
    @Test fun `시계 오차는 설정 범위까지만 허용한다`() {
        assertNotNull(InternalClaimsPolicy.validate(valid.copy(issuedAt=now.plusSeconds(5)),settings,now))
        assertNotNull(InternalClaimsPolicy.validate(valid.copy(issuedAt=now.minusSeconds(60),expiresAt=now.minusSeconds(4)),settings,now))
    }
    @Test fun `비활성 설정은 키 없이 가능하지만 활성 설정은 충분한 키를 요구한다`() {
        InternalApiSettings()
        for(key in listOf("", "not-base64", Base64.getEncoder().encodeToString(ByteArray(31)))) {
            assertFailsWith<IllegalArgumentException> { InternalApiSettings(enabled=true,secretBase64=key) }
        }
        assertFailsWith<IllegalArgumentException> { InternalApiSettings(enabled=true,maxTtlSeconds=301,secretBase64=Base64.getEncoder().encodeToString(ByteArray(32))) }
    }
    @Test fun `키는 문자열 표현에서 숨기고 반환된 배열 수정이 원본에 영향을 주지 않는다`() {
        val copy=settings.verificationKey();copy[0]=99
        assertEquals(0.toByte(),settings.verificationKey()[0]);assertTrue("redacted" in settings.toString())
    }
}
