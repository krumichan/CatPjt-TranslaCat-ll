package jp.co.translacat.languagelearning.bootstrap

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import jp.co.translacat.languagelearning.shared.http.InternalApiError
import jp.co.translacat.languagelearning.shared.security.*
import java.time.Clock


internal fun Application.configureInternalAuthentication(
    settings: InternalApiSettings, clock: Clock = Clock.systemUTC()
) {
    check(settings.enabled)
    install(Authentication) {
        jwt(INTERNAL_AUTH) {
            realm = "translacat-ll-internal"
            verifier(
                JWT.require(Algorithm.HMAC256(settings.verificationKey()))
                    .withIssuer(settings.issuer)
                    .withAudience(settings.audience)
                    .withClaim("service", settings.callerService)
                    .withClaim("tokenUse", "ll-internal")
                    .acceptLeeway(settings.clockSkewSeconds)
                    .build(),
            )
            validate { credential ->
                // 클레임의 타입 오류도 인증 실패다. userId/role 헤더를 신뢰하는 우회 경로는 없다.
                try {
                    val payload = credential.payload
                    InternalClaimsPolicy.validate(
                        InternalClaims(
                            payload.subject,
                            payload.getClaim("roles").asList(String::class.java),
                            payload.issuedAt?.toInstant(),
                            payload.expiresAt?.toInstant()
                        ),
                        settings, clock.instant(),
                    )?.let(::InternalUserPrincipal)
                } catch (_: Exception) {
                    null
                }
            }
            challenge { _, _ ->
                call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer realm=\"translacat-ll-internal\"")
                call.respond(
                    HttpStatusCode.Unauthorized, InternalApiError("INTERNAL_AUTH_REQUIRED", "유효한 내부 호출 인증이 필요합니다.")
                )
            }
        }
    }
}
