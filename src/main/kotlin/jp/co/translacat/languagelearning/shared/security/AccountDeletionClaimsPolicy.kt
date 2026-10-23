package jp.co.translacat.languagelearning.shared.security

import java.time.Instant

internal const val ACCOUNT_DELETION_AUTH = "ll-account-deletion"

internal data class AccountDeletionPrincipal(val userId: Long)

/** 서명/issuer/audience/service/tokenUse 검증 뒤에도 삭제 용도·소유자·짧은 기한을 다시 고정한다. */
internal object AccountDeletionClaimsPolicy {
    fun validate(
        subject: String?, scopes: List<String>?, roles: List<String>?, issued: Instant?, expires: Instant?,
        settings: InternalApiSettings, now: Instant,
    ): AccountDeletionPrincipal? {
        if (!settings.enabled || scopes != listOf("accounts:delete") || !roles.isNullOrEmpty()) return null
        if (subject == null || !Regex("[1-9][0-9]{0,18}").matches(subject)) return null
        val userId = subject.toLongOrNull()?.takeIf { it > 0 } ?: return null
        if (issued == null || expires == null || !expires.isAfter(issued)) return null
        if (expires.isAfter(issued.plusSeconds(minOf(settings.maxTtlSeconds, 120)))) return null
        if (issued.isAfter(now.plusSeconds(settings.clockSkewSeconds))) return null
        if (!expires.isAfter(now.minusSeconds(settings.clockSkewSeconds))) return null
        return AccountDeletionPrincipal(userId)
    }
}
