package jp.co.translacat.languagelearning.shared.security

import java.time.Instant

/** 이 검사는 서명 검증을 대신하지 않는다. JWT 라이브러리의 서명/발급자 검증 이후에만 호출한다. */
internal data class InternalClaims(
    val subject: String?,
    val roles: List<String>?,
    val issuedAt: Instant?,
    val expiresAt: Instant?,
)

internal data class VerifiedInternalUser(val userId: Long, val roles: Set<String>) {
    val administrator: Boolean get() = "ADMIN" in roles
}

internal object InternalClaimsPolicy {
    fun validate(claims: InternalClaims, settings: InternalApiSettings, now: Instant): VerifiedInternalUser? {
        if (!settings.enabled) return null
        val subject = claims.subject ?: return null
        if (!Regex("[1-9][0-9]{0,18}").matches(subject)) return null
        val id = subject.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val roles = claims.roles ?: return null
        if (roles.isEmpty() || roles.size != roles.toSet().size || roles.any {
                it !in setOf(
                    "USER", "ADMIN",
                )
            }) return null
        val issued = claims.issuedAt ?: return null
        val expires = claims.expiresAt ?: return null
        // 먼저 시간의 순서를 확인하므로 exp-iat 계산으로 비정상적인 토큰을 허용하지 않는다.
        if (!expires.isAfter(issued) || expires.isAfter(issued.plusSeconds(settings.maxTtlSeconds))) return null
        if (issued.isAfter(now.plusSeconds(settings.clockSkewSeconds))) return null
        if (!expires.isAfter(now.minusSeconds(settings.clockSkewSeconds))) return null
        return VerifiedInternalUser(id, roles.toSet())
    }
}
