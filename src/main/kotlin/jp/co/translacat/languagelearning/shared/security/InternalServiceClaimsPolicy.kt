package jp.co.translacat.languagelearning.shared.security

import java.time.Instant

internal data class InternalServiceClaims(
    val subject: String?,
    val scopes: List<String>?,
    val roles: List<String>?,
    val issuedAt: Instant?,
    val expiresAt: Instant?,
)

/** 서명/issuer/audience/service/tokenUse를 검증한 뒤 추가 적용한다. */
internal object InternalServiceClaimsPolicy {
    fun validate(claims: InternalServiceClaims, settings: InternalApiSettings, now: Instant): InternalServicePrincipal? {
        if (!settings.enabled || claims.subject != settings.callerService) return null
        if (claims.scopes != listOf("settings:read") || !claims.roles.isNullOrEmpty()) return null
        val issued = claims.issuedAt ?: return null
        val expires = claims.expiresAt ?: return null
        if (!expires.isAfter(issued) || expires.isAfter(issued.plusSeconds(settings.maxTtlSeconds))) return null
        if (issued.isAfter(now.plusSeconds(settings.clockSkewSeconds))) return null
        if (!expires.isAfter(now.minusSeconds(settings.clockSkewSeconds))) return null
        return InternalServicePrincipal(settings.callerService)
    }
}
