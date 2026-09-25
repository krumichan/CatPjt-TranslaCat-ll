package jp.co.translacat.languagelearning.bootstrap

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import jp.co.translacat.languagelearning.shared.http.InternalApiError
import jp.co.translacat.languagelearning.shared.security.*
import java.time.Clock

/** 기존 Settings 토큰의 권한을 넓히지 않고 키워드 전용 용도를 추가한다. */
internal fun AuthenticationConfig.configureKeywordAuthentication(settings: InternalApiSettings, clock: Clock) {
    jwt(KEYWORD_AUTH) {
        realm = "translacat-ll-keywords"
        verifier(
            JWT.require(Algorithm.HMAC256(settings.verificationKey()))
                .withIssuer(settings.issuer)
                .withAudience(settings.audience)
                .withClaim("service", settings.callerService)
                .withClaim("tokenUse", "ll-keywords")
                .acceptLeeway(settings.clockSkewSeconds)
                .build(),
        )
        validate { credential ->
            try {
                val payload = credential.payload
                val started = payload.getClaim("keywordLearningStarted").asBoolean()
                val user = InternalClaimsPolicy.validate(
                    InternalClaims(
                        payload.subject,
                        payload.getClaim("roles").asList(String::class.java),
                        payload.issuedAt?.toInstant(),
                        payload.expiresAt?.toInstant(),
                    ),
                    settings, clock.instant(),
                )
                if (user == null || started == null) null else KeywordPrincipal(user, started)
            } catch (_: Exception) {
                null
            }
        }
        challenge { _, _ ->
            call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer realm=\"translacat-ll-keywords\"")
            call.respond(
                HttpStatusCode.Unauthorized, InternalApiError("INTERNAL_AUTH_REQUIRED", "유효한 키워드 내부 인증이 필요합니다."),
            )
        }
    }
}
