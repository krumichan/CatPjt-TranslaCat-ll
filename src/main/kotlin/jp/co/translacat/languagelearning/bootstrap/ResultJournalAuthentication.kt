package jp.co.translacat.languagelearning.bootstrap

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import jp.co.translacat.languagelearning.shared.http.InternalApiError
import jp.co.translacat.languagelearning.shared.security.InternalApiSettings
import jp.co.translacat.languagelearning.shared.security.RESULT_JOURNAL_AUTH
import jp.co.translacat.languagelearning.shared.security.ResultJournalClaimsPolicy
import java.time.Clock

internal fun AuthenticationConfig.configureResultJournalAuthentication(settings: InternalApiSettings, clock: Clock) {
    jwt(RESULT_JOURNAL_AUTH) {
        realm = "translacat-ll-learning-results"
        verifier(
            JWT.require(Algorithm.HMAC256(settings.verificationKey()))
                .withIssuer(settings.issuer)
                .withAudience(settings.audience)
                .withSubject(settings.callerService)
                .withClaim("service", settings.callerService)
                .withClaim("tokenUse", "ll-learning-results-v1")
                .acceptLeeway(settings.clockSkewSeconds)
                .build()
        )
        validate { credential ->
            try {
                val payload = credential.payload
                ResultJournalClaimsPolicy.validate(
                    payload.subject,
                    payload.getClaim("scopes").asList(String::class.java),
                    payload.getClaim("roles").asList(String::class.java),
                    payload.issuedAt?.toInstant(),
                    payload.expiresAt?.toInstant(),
                    settings,
                    clock.instant()
                )
            } catch (_: Exception) {
                null
            }
        }
        challenge { _, _ ->
            call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer realm=\"translacat-ll-learning-results\"")
            call.respond(HttpStatusCode.Unauthorized, InternalApiError("RESULT_AUTH_REQUIRED", "유효한 결과 전달 인증이 필요합니다."))
        }
    }
}
