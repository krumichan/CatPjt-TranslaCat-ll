package jp.co.translacat.languagelearning.shared.security

import java.time.Instant

internal const val GROWTH_AUTH = "ll-growth-v1"

internal data class GrowthPrincipal(val service: String)

/** 기존 서비스와 같은 발급·만료 제한을 적용하되 결과 기록 전용 scope만 허용한다. */
internal object GrowthClaimsPolicy {
    fun validate(
        subject: String?,
        scopes: List<String>?,
        roles: List<String>?,
        issuedAt: Instant?,
        expiresAt: Instant?,
        settings: InternalApiSettings,
        now: Instant,
    ): GrowthPrincipal? {
        if (scopes != listOf("growth:write") || !roles.isNullOrEmpty()) return null
        if (!settings.enabled || subject != settings.callerService) return null
        val issued = issuedAt ?: return null
        val expires = expiresAt ?: return null
        if (!expires.isAfter(issued) || expires.isAfter(issued.plusSeconds(settings.maxTtlSeconds))) return null
        if (issued.isAfter(now.plusSeconds(settings.clockSkewSeconds))) return null
        if (!expires.isAfter(now.minusSeconds(settings.clockSkewSeconds))) return null
        return GrowthPrincipal(settings.callerService)
    }
}
