package jp.co.translacat.languagelearning.features.overview

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.auth.authenticate
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import jp.co.translacat.languagelearning.bootstrap.configureInternalAuthentication
import jp.co.translacat.languagelearning.bootstrap.configureSerialization
import jp.co.translacat.languagelearning.bootstrap.configureStatusPages
import jp.co.translacat.languagelearning.features.overview.api.overviewHistoryDetailRoute
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingFailure
import jp.co.translacat.languagelearning.shared.security.INTERNAL_AUTH
import jp.co.translacat.languagelearning.shared.security.InternalApiSettings
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.Base64
import java.util.Date
import kotlin.test.*

class OverviewHistoryRouteTest {
    private val key = ByteArray(32) { (it + 23).toByte() }
    private val auth = InternalApiSettings(enabled = true, secretBase64 = Base64.getEncoder().encodeToString(key))
    private fun token(userId: Long): String = JWT.create()
        .withIssuer(auth.issuer).withAudience(auth.audience).withSubject(userId.toString())
        .withClaim("service", auth.callerService).withClaim("tokenUse", "ll-internal")
        .withArrayClaim("roles", arrayOf("USER")).withIssuedAt(Date.from(Instant.now()))
        .withExpiresAt(Date.from(Instant.now().plusSeconds(60))).sign(Algorithm.HMAC256(key))

    @Test
    fun `Speaking 원본 접근 차단은 직접 조회와 같은 오류를 반환하고 인증 owner만 전달한다`() = testApplication {
        // 준비: 실제 route/auth/error 매핑에 기능 읽기 경계의 정상·차단 응답만 주입한다.
        environment { config = MapApplicationConfig() }
        val calls = mutableListOf<Long>()
        application {
            configureSerialization(); configureStatusPages(); configureInternalAuthentication(auth)
            routing {
                authenticate(INTERNAL_AUTH) {
                    route("/internal/v1/language-learning/overview") {
                        overviewHistoryDetailRoute { userId, activityId ->
                            calls += userId
                            assertEquals("SPEAKING:-1", activityId)
                            if (userId != 41L) throw SpeakingFailure("SESSION_NOT_FOUND")
                            buildJsonObject { put("activityId", activityId) }
                        }
                    }
                }
            }
        }

        // 실행: URL의 추가 owner 값은 인증 owner를 바꾸지 않는다.
        val path = "/internal/v1/language-learning/overview/history/SPEAKING:-1"
        assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
        val own = client.get(path) { bearerAuth(token(41)) }
        val other = client.get("$path?userId=41") { bearerAuth(token(42)) }

        // 검증: 권한 실패를 500이나 성공으로 바꾸지 않고 상세/원문을 노출하지 않는다.
        assertEquals(HttpStatusCode.OK, own.status)
        assertEquals(HttpStatusCode.BadRequest, other.status)
        val error = Json.parseToJsonElement(other.bodyAsText()).jsonObject
        assertEquals("SESSION_NOT_FOUND", error.getValue("code").jsonPrimitive.content)
        assertEquals(setOf("code", "message"), error.keys)
        assertEquals(listOf(41L, 42L), calls)
    }

    @Test
    fun `Speaking의 명시적 금지와 저장소 장애는 서로 다른 실패 상태로 유지한다`() = testApplication {
        // 준비
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureStatusPages(); configureInternalAuthentication(auth)
            routing {
                authenticate(INTERNAL_AUTH) {
                    route("/internal/v1/language-learning/overview") {
                        overviewHistoryDetailRoute { _, activityId ->
                            if (activityId == "SPEAKING:-1") throw SpeakingFailure("FORBIDDEN", 403)
                            error("synthetic repository failure")
                        }
                    }
                }
            }
        }

        // 실행 및 검증: 알려진 기능 오류만 매핑하며 일반 오류는 기존 500을 보존한다.
        val path = "/internal/v1/language-learning/overview/history/"
        for ((id, status, code) in listOf(
            Triple("SPEAKING:-1", HttpStatusCode.Forbidden, "FORBIDDEN"),
            Triple("SPEAKING:-2", HttpStatusCode.InternalServerError, "INTERNAL_ERROR"),
        )) {
            val response = client.get(path + id) { bearerAuth(token(41)) }
            assertEquals(status, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(code, body.getValue("code").jsonPrimitive.content)
            assertEquals(setOf("code", "message"), body.keys)
        }
    }
}
