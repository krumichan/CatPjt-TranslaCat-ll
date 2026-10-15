package jp.co.translacat.languagelearning.shared.security

import java.time.Instant

internal const val WEB_AI_SERVICE_AUTH = "ll-web-ai-service"

/** 서비스 토큰은 Web AI capability 전달에만 사용하며 사용자·관리자 토큰으로 승격하지 않는다. */
internal object WebAiServiceClaimsPolicy {
    fun validate(claims: InternalServiceClaims, settings: InternalApiSettings, now: Instant): InternalServicePrincipal? {
        if (claims.scopes != listOf("web-ai:external")) return null
        return InternalServiceClaimsPolicy.validate(claims.copy(scopes = listOf("settings:read")), settings, now)
    }
}
