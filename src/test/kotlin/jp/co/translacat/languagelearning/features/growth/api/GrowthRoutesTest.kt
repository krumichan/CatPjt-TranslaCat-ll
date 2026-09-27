package jp.co.translacat.languagelearning.features.growth.api

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import jp.co.translacat.languagelearning.bootstrap.configureInternalAuthentication
import jp.co.translacat.languagelearning.bootstrap.configureSerialization
import jp.co.translacat.languagelearning.bootstrap.configureStatusPages
import jp.co.translacat.languagelearning.features.growth.application.GrowthProjector
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthChange
import jp.co.translacat.languagelearning.shared.security.InternalApiSettings
import jp.co.translacat.languagelearning.support.MemoryGrowthUnitOfWork
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrowthRoutesTest {
    private val key = ByteArray(32) { it.toByte() }
    private val settings = InternalApiSettings(enabled = true, secretBase64 = Base64.getEncoder().encodeToString(key))
    private val read = "/internal/v1/language-learning/growth/snapshot"
    private fun token(user: Long = 123, use: String = "ll-internal"): String {
        val now = Instant.now()
        return JWT.create().withIssuer(settings.issuer).withAudience(settings.audience).withSubject(user.toString())
            .withClaim("service", settings.callerService).withClaim("tokenUse", use)
            .withArrayClaim("roles", arrayOf("USER")).withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(60))).sign(Algorithm.HMAC256(key))
    }

    @Test
    fun `현재 사용자 인증만 허용하고 옛 수신 endpoint를 등록하지 않는다`() = testApplication {
        // 준비
        environment { config = MapApplicationConfig() }
        val db = MemoryGrowthUnitOfWork()
        application {
            configureSerialization(); configureStatusPages(); configureInternalAuthentication(settings)
            routing { growthRoutes(db) }
        }

        // 실행 및 검증
        assertEquals(HttpStatusCode.Unauthorized, client.post(read).status)
        for (use in listOf("ll-growth-v1", "ll-learning-results-v1", "ll-settings-service")) {
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.post(read) {
                    bearerAuth(token(use = use)); contentType(ContentType.Application.Json); setBody("{}")
                }.status,
            )
        }
        for (path in listOf(
            "/internal/v1/service/language-learning/growth/commands", "/internal/v1/service/language-learning/results",
        )) {
            assertEquals(HttpStatusCode.NotFound, client.post(path) { bearerAuth(token()) }.status)
        }
        assertTrue(db.state.profiles.isEmpty())
    }

    @Test
    fun `현재 조회는 사용자별 커밋 자료만 반환하며 빈 계정을 생성하지 않는다`() = testApplication {
        // 준비
        environment { config = MapApplicationConfig() }
        val db = MemoryGrowthUnitOfWork()
        val now = LocalDateTime.parse("2026-09-27T01:00:00")
        GrowthProjector(db.state).apply(123, GrowthChange.KeywordsSelected(now.toLocalDate(), listOf("travel")), now)
        application {
            configureSerialization(); configureStatusPages(); configureInternalAuthentication(settings)
            routing { growthRoutes(db) }
        }
        suspend fun readFor(user: Long) = client.post(read) {
            bearerAuth(token(user)); contentType(ContentType.Application.Json); setBody("{\"masteryKeys\":null}")
        }

        // 실행
        val own = readFor(123)
        val other = readFor(456)

        // 검증
        assertEquals(HttpStatusCode.OK, own.status)
        val body = Json.parseToJsonElement(own.bodyAsText()).jsonObject
        assertEquals(setOf("userId", "profile", "masteries", "signals"), body.keys)
        assertEquals(1, body.getValue("masteries").jsonArray.size)
        assertEquals(0, Json.parseToJsonElement(other.bodyAsText()).jsonObject.getValue("masteries").jsonArray.size)
        assertEquals(1, db.state.masteries.size)
        assertTrue(db.state.profiles.isEmpty())
    }

    @Test
    fun `수신 필드 잘못된 JSON 비활성 계정 및 크기 초과를 안전하게 거부한다`() = testApplication {
        // 준비
        environment { config = MapApplicationConfig() }
        val db = MemoryGrowthUnitOfWork()
        application {
            configureSerialization(); configureStatusPages(); configureInternalAuthentication(settings)
            routing { growthRoutes(db) }
        }
        suspend fun send(body: String) = client.post(read) {
            bearerAuth(token()); contentType(ContentType.Application.Json); setBody(body)
        }

        // 실행 및 검증
        for (body in listOf(
            "{", "{\"sourceInstanceId\":\"old\"}", "{\"minimumSequence\":1}", "{\"previewOperations\":[]}",
            "{\"userId\":456}", "{\"masteryKeys\":[1]}",
        )) {
            assertEquals(HttpStatusCode.BadRequest, send(body).status)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, send(" ".repeat(GROWTH_MAX_BODY_BYTES + 1)).status)
        db.state.inactive.add(123)
        assertEquals(HttpStatusCode.Forbidden, send("{}").status)
        assertTrue(db.state.masteries.isEmpty())
    }

    @Test
    fun `활동 조회는 현재 cursor 계약만 받으며 날짜와 소스를 검증한다`() = testApplication {
        // 준비
        environment { config = MapApplicationConfig() }
        val db = MemoryGrowthUnitOfWork()
        application {
            configureSerialization(); configureStatusPages(); configureInternalAuthentication(settings)
            routing { growthRoutes(db) }
        }
        val path = "/internal/v1/language-learning/growth/activities?from=2026-09-27&to=2026-09-27"

        // 실행 및 검증
        val response = client.get(path) { bearerAuth(token()) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            setOf("userId", "activities", "nextAfterId", "projectionRevision"),
            Json.parseToJsonElement(response.bodyAsText()).jsonObject.keys,
        )
        for (extra in listOf("&minimumSequence=1", "&sourceInstanceId=old", "&afterId=-1", "&source=INVALID")) {
            assertEquals(HttpStatusCode.BadRequest, client.get(path + extra) { bearerAuth(token()) }.status)
        }
    }
}
