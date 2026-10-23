package jp.co.translacat.languagelearning.features.accountdeletion

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import jp.co.translacat.languagelearning.bootstrap.*
import jp.co.translacat.languagelearning.features.accountdeletion.api.accountDeletionRoutes
import jp.co.translacat.languagelearning.features.accountdeletion.application.AccountDeletionService
import jp.co.translacat.languagelearning.shared.security.InternalApiSettings
import jp.co.translacat.languagelearning.shared.security.INTERNAL_AUTH
import jp.co.translacat.languagelearning.shared.security.KEYWORD_AUTH
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.*
import kotlin.test.*

class AccountDeletionRoutesTest {
    private val key = ByteArray(32) { 91 }
    private val settings = InternalApiSettings(enabled = true, secretBase64 = Base64.getEncoder().encodeToString(key))
    private val path = "/internal/v1/language-learning/accounts/71/deletion"
    private fun body() = """{"operationId":"${UUID.randomUUID()}","requestedAtUtc":"${Instant.now().minusSeconds(1)}"}"""

    private fun token(sub: String = "71", use: String = "ll-account-deletion", scopes: List<String> = listOf("accounts:delete"),
                      issuer: String = "translacat-be", audience: String = "translacat-ll", service: String = "translacat-be",
                      ttl: Long = 60, wrongKey: Boolean = false, roles: List<String>? = null): String {
        val now = Instant.now()
        val builder = JWT.create().withIssuer(issuer).withAudience(audience).withSubject(sub)
            .withClaim("service", service).withClaim("tokenUse", use).withArrayClaim("scopes", scopes.toTypedArray())
            .withIssuedAt(Date.from(now)).withExpiresAt(Date.from(now.plusSeconds(ttl)))
        if (roles != null) builder.withArrayClaim("roles", roles.toTypedArray())
        return builder.sign(Algorithm.HMAC256(if (wrongKey) ByteArray(32) { 1 } else key))
    }

    @Test
    fun `익명과 기존 사용자 및 다른 scope 토큰은 삭제 body를 읽기 전에 거부한다`() = app { store ->
        // 준비
        val invalid = listOf(null, token(use = "ll-internal"), token(scopes = listOf("settings:read")),
            token(issuer = "other"), token(audience = "other"), token(service = "other"),
            token(ttl = 121), token(wrongKey = true), token(sub = "071"))

        // 실행과 검증
        for (auth in invalid) {
            val response = client.post(path) { if (auth != null) bearerAuth(auth); setBody("{") }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }
        assertEquals(0, store.prepared)
    }

    @Test
    fun `path user와 JWT subject 불일치는 헤더로 바꿀 수 없다`() = app { store ->
        // 실행
        val response = client.post(path) {
            bearerAuth(token(sub = "72")); header("X-User-Id", "71")
            contentType(ContentType.Application.Json); setBody(body())
        }

        // 검증
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(0, store.prepared)
    }

    @Test
    fun `readiness는 읽기만 하며 정확한 버전과 준비 상태를 반환한다`() = app { store ->
        // 실행
        val response = client.get("$path/capabilities") { bearerAuth(token()) }

        // 검증
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(setOf("version", "ready"), Json.parseToJsonElement(response.bodyAsText()).jsonObject.keys)
        assertTrue(response.bodyAsText().contains("ll-account-deletion-v1"))
        assertEquals(0, store.prepared)
    }

    @Test
    fun `준비되지 않은 저장소는 tombstone 전에 503으로 거부한다`() = app(ready = false) { store ->
        // 실행
        val response = client.post(path) { bearerAuth(token()); contentType(ContentType.Application.Json); setBody(body()) }

        // 검증
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(0, store.prepared)
    }

    @Test
    fun `중복 알 수 없는 필드와 큰 body는 접수하지 않는다`() = app { store ->
        // 준비
        val valid = body()
        val invalid = listOf("{", "[]", "{}", valid.replace("}", ",\"extra\":true}"),
            valid.replace("}", ",\"operationId\":\"${UUID.randomUUID()}\"}"),
            valid.replace("}", ",\"operation\\u0049d\":\"${UUID.randomUUID()}\"}"), " ".repeat(2049),
            valid.replace("\"operationId\":\"", "\"operationId\":71,\"other\":\""))

        // 실행과 검증
        for (value in invalid) {
            val response = client.post(path) { bearerAuth(token()); contentType(ContentType.Application.Json); setBody(value) }
            assertTrue(response.status in setOf(HttpStatusCode.BadRequest, HttpStatusCode.PayloadTooLarge), response.status.toString())
        }
        assertEquals(0, store.prepared)
    }

    @Test
    fun `완료와 pending 응답은 같은 엄격한 wire 필드를 사용한다`() = app { store ->
        // 실행
        val response = client.post(path) { bearerAuth(token()); contentType(ContentType.Application.Json); setBody(body()) }

        // 검증
        assertEquals(HttpStatusCode.OK, response.status)
        val wire = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("operationId", "userId", "status", "pendingObjects"), wire.keys)
        assertEquals("COMPLETED", wire.getValue("status").jsonPrimitive.content)
        assertEquals(1, store.prepared)
    }

    @Test
    fun `tombstone은 남은 내부 사용자 조회를 막지만 동일 삭제 operation 재호출은 허용한다`() = app(userActive = false) { store ->
        // 준비와 실행
        val regular = client.get("/test-user") { bearerAuth(token(use = "ll-internal", roles = listOf("USER"))) }
        val keyword = client.get("/test-keyword") { bearerAuth(token(use = "ll-keywords", roles = listOf("USER"))) }
        val deletion = client.post(path) { bearerAuth(token()); contentType(ContentType.Application.Json); setBody(body()) }

        // 검증
        assertEquals(HttpStatusCode.Unauthorized, regular.status)
        assertEquals(HttpStatusCode.Unauthorized, keyword.status)
        assertEquals(HttpStatusCode.OK, deletion.status)
        assertEquals(1, store.prepared)
    }

    @Test
    fun `이스케이프된 정상 필드 이름은 JSON 의미를 보존한다`() = app { store ->
        // 준비: 같은 이름을 두 번 쓰지 않은 JSON의 표준 유니코드 이스케이프다.
        val escaped = body().replace("operationId", "operation\\u0049d")

        // 실행
        val response = client.post(path) {
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            setBody(escaped)
        }

        // 검증
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, store.prepared)
    }

    private fun app(ready: Boolean = true, userActive: Boolean = true, block: suspend ApplicationTestBuilder.(DeletionFixtureStore) -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        val store = DeletionFixtureStore()
        application {
            configureSerialization()
            configureStatusPages()
            configureInternalAuthentication(settings, userActive = { userActive })
            routing {
                accountDeletionRoutes(AccountDeletionService(store, {})) { ready }
                authenticate(INTERNAL_AUTH) { get("/test-user") { call.respond("synthetic") } }
                authenticate(KEYWORD_AUTH) { get("/test-keyword") { call.respond("synthetic") } }
            }
        }
        block(store)
    }
}
