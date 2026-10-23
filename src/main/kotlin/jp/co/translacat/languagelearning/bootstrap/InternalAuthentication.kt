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
    settings: InternalApiSettings, clock: Clock = Clock.systemUTC(),
    userActive: suspend (Long) -> Boolean = { true },
) {
    check(settings.enabled)
    install(Authentication) {
        configureKeywordAuthentication(settings, clock, userActive)
        // 계정 삭제 토큰은 기존 USER/ADMIN/설정 조회 토큰과 용도를 공유하지 않는다.
        jwt(ACCOUNT_DELETION_AUTH) {
            realm = "translacat-ll-account-deletion"
            verifier(
                JWT.require(Algorithm.HMAC256(settings.verificationKey()))
                    .withIssuer(settings.issuer).withAudience(settings.audience)
                    .withClaim("service", settings.callerService)
                    .withClaim("tokenUse", "ll-account-deletion")
                    .acceptLeeway(settings.clockSkewSeconds).build(),
            )
            validate { credential ->
                runCatching {
                    val payload = credential.payload
                    AccountDeletionClaimsPolicy.validate(
                        payload.subject, payload.getClaim("scopes").asList(String::class.java),
                        payload.getClaim("roles").asList(String::class.java),
                        payload.issuedAt?.toInstant(), payload.expiresAt?.toInstant(), settings, clock.instant(),
                    )
                }.getOrNull()
            }
            challenge { _, _ ->
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.respond(HttpStatusCode.Unauthorized,
                    InternalApiError("ACCOUNT_DELETION_AUTH_REQUIRED", "계정 삭제 전용 인증이 필요합니다."))
            }
        }
        jwt(WEB_AI_SERVICE_AUTH) {
            realm = "translacat-ll-web-ai-service"
            verifier(
                JWT.require(Algorithm.HMAC256(settings.verificationKey()))
                    .withIssuer(settings.issuer)
                    .withAudience(settings.audience)
                    .withSubject(settings.callerService)
                    .withClaim("service", settings.callerService)
                    .withClaim("tokenUse", "ll-web-ai-service")
                    .acceptLeeway(settings.clockSkewSeconds)
                    .build(),
            )
            validate { credential ->
                try {
                    val payload = credential.payload
                    WebAiServiceClaimsPolicy.validate(
                        InternalServiceClaims(
                            payload.subject, payload.getClaim("scopes").asList(String::class.java),
                            payload.getClaim("roles").asList(String::class.java),
                            payload.issuedAt?.toInstant(), payload.expiresAt?.toInstant(),
                        ), settings, clock.instant(),
                    )
                } catch (_: Exception) {
                    null
                }
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized,
                    InternalApiError("INTERNAL_SERVICE_AUTH_REQUIRED", "유효한 서비스 호출 인증이 필요합니다."))
            }
        }
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
                    val user = InternalClaimsPolicy.validate(
                        InternalClaims(
                            payload.subject,
                            payload.getClaim("roles").asList(String::class.java),
                            payload.issuedAt?.toInstant(),
                            payload.expiresAt?.toInstant(),
                        ),
                        settings, clock.instant(),
                    )
                    if (user != null && userActive(user.userId)) InternalUserPrincipal(user) else null
                } catch (_: Exception) {
                    null
                }
            }
            challenge { _, _ ->
                call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer realm=\"translacat-ll-internal\"")
                call.respond(
                    HttpStatusCode.Unauthorized, InternalApiError("INTERNAL_AUTH_REQUIRED", "유효한 내부 호출 인증이 필요합니다."),
                )
            }
        }
        // 서비스 조회 토큰은 사용자용 토큰과 용도가 다르며 관리자 PATCH에는 사용할 수 없다.
        jwt(SETTINGS_SERVICE_AUTH) {
            realm = "translacat-ll-settings-service"
            verifier(
                JWT.require(Algorithm.HMAC256(settings.verificationKey()))
                    .withIssuer(settings.issuer)
                    .withAudience(settings.audience)
                    .withSubject(settings.callerService)
                    .withClaim("service", settings.callerService)
                    .withClaim("tokenUse", "ll-settings-service")
                    .acceptLeeway(settings.clockSkewSeconds)
                    .build(),
            )
            validate { credential ->
                try {
                    val payload = credential.payload
                    InternalServiceClaimsPolicy.validate(
                        InternalServiceClaims(
                            payload.subject,
                            payload.getClaim("scopes").asList(String::class.java),
                            payload.getClaim("roles").asList(String::class.java),
                            payload.issuedAt?.toInstant(),
                            payload.expiresAt?.toInstant(),
                        ),
                        settings, clock.instant(),
                    )
                } catch (_: Exception) {
                    null
                }
            }
            challenge { _, _ ->
                call.response.headers.append(
                    HttpHeaders.WWWAuthenticate, "Bearer realm=\"translacat-ll-settings-service\"",
                )
                call.respond(
                    HttpStatusCode.Unauthorized,
                    InternalApiError("INTERNAL_SERVICE_AUTH_REQUIRED", "유효한 서비스 조회 인증이 필요합니다."),
                )
            }
        }
    }
}
