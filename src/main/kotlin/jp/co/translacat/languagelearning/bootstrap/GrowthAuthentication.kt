package jp.co.translacat.languagelearning.bootstrap

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import jp.co.translacat.languagelearning.shared.http.InternalApiError
import jp.co.translacat.languagelearning.shared.security.GROWTH_AUTH
import jp.co.translacat.languagelearning.shared.security.GrowthClaimsPolicy
import jp.co.translacat.languagelearning.shared.security.InternalApiSettings
import java.time.Clock

internal fun AuthenticationConfig.configureGrowthAuthentication(settings: InternalApiSettings, clock: Clock) {
    jwt(GROWTH_AUTH) {
        realm = "translacat-ll-growth"
        verifier(
            JWT.require(Algorithm.HMAC256(settings.verificationKey()))
                .withIssuer(settings.issuer)
                .withAudience(settings.audience)
                .withSubject(settings.callerService)
                .withClaim("service", settings.callerService)
                .withClaim("tokenUse", "ll-growth-v1")
                .acceptLeeway(settings.clockSkewSeconds)
                .build(),
        )
        validate { credential ->
            try {
                val payload = credential.payload
                GrowthClaimsPolicy.validate(
                    payload.subject,
                    payload.getClaim("scopes").asList(String::class.java),
                    payload.getClaim("roles").asList(String::class.java),
                    payload.issuedAt?.toInstant(),
                    payload.expiresAt?.toInstant(),
                    settings,
                    clock.instant(),
                )
            } catch (_: Exception) {
                null
            }
        }
        challenge { _, _ ->
            call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer realm=\"translacat-ll-growth\"")
            call.respond(HttpStatusCode.Unauthorized, InternalApiError("GROWTH_AUTH_REQUIRED", "유효한 성장 전달 인증이 필요합니다."))
        }
    }
}
