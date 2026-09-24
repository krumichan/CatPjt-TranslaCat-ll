package jp.co.translacat.languagelearning.features.settings.api

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
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsResult
import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsSnapshot
import jp.co.translacat.languagelearning.features.settings.domain.model.ConfiguredLanguagePair
import jp.co.translacat.languagelearning.shared.security.InternalApiSettings
import java.time.Instant
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import jp.co.translacat.languagelearning.support.SettingsFixtures as F

class SettingsServiceRoutesTest {
    private val key = ByteArray(32) { it.toByte() }
    private val auth = InternalApiSettings(enabled = true, secretBase64 = Base64.getEncoder().encodeToString(key))
    private val path = "/internal/v1/service/language-learning/settings"
    private fun token(
        use: String = "ll-settings-service", scopes: List<String> = listOf("settings:read"), expired: Boolean = false
    ): String {
        val now = Instant.now().minusSeconds(if (expired) 500 else 0)
        val builder = JWT.create()
            .withIssuer("translacat-be")
            .withAudience("translacat-ll")
            .withClaim("service", "translacat-be")
            .withClaim("tokenUse", use)
            .withSubject(if (use == "ll-settings-service") "translacat-be" else "123")
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(120)))
        if (use == "ll-settings-service") builder.withArrayClaim("scopes", scopes.toTypedArray())
        else builder.withArrayClaim("roles", arrayOf("ADMIN"))
        return builder.sign(Algorithm.HMAC256(key))
    }

    private class Service : SettingsServiceOperations {
        var calls = 0
        override suspend fun userSnapshot(userId: Long): UserSettingsSnapshot {
            calls++; return UserSettingsSnapshot(
                userId, F.now.toLocalDate(), UserSettingsResult(F.configured(userId), F.policy())
            )
        }

        override suspend fun learningDate(userId: Long) = F.now.toLocalDate().also { calls++ }
        override suspend fun adminPolicy() = F.admin().also { calls++ }
        override suspend fun listeningPolicy() = error("범위 밖")
        override suspend fun configuredLanguagePairs() = listOf(ConfiguredLanguagePair("ko", "ja")).also { calls++ }
    }

    private fun app(block: suspend ApplicationTestBuilder.(Service) -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        val service = Service()
        application {
            configureSerialization(); configureStatusPages(); configureInternalAuthentication(auth)
            routing { settingsServiceRoutes(service) }
        }
        block(service)
    }

    @Test
    fun `인증 없이 서비스 조회할 수 없다`() = app { service ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("$path/admin").status); assertEquals(0, service.calls)
    }

    @Test
    fun `사용자와 관리자 토큰을 서비스 토큰으로 혼용하지 않는다`() = app { service ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("$path/admin") { bearerAuth(token("ll-internal")) }.status)
        assertEquals(0, service.calls)
    }

    @Test
    fun `read scope만 수락하고 write scope와 만료 토큰은 거부한다`() = app { service ->
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("$path/admin") { bearerAuth(token(scopes = listOf("settings:write"))) }.status
        )
        assertEquals(
            HttpStatusCode.Unauthorized, client.get("$path/admin") { bearerAuth(token(expired = true)) }.status
        )
        assertEquals(0, service.calls)
    }

    @Test
    fun `snapshot에는 ID 날짜 revision이 포함된다`() = app { service ->
        val response = client.get("$path/users/123") { bearerAuth(token()); header("X-User-Id", "999") }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(response.bodyAsText()).toString()
        assertTrue(body.contains("\"userId\":123")); assertTrue(body.contains("\"revision\"")); assertEquals(
        1, service.calls
    )
    }

    @Test
    fun `무효 사용자 경로는 업무 로직 앞에서 거부된다`() = app { service ->
        for (id in listOf("0", "-1", "abc", "999999999999999999999")) {
            assertEquals(HttpStatusCode.BadRequest, client.get("$path/users/$id") { bearerAuth(token()) }.status)
        }
        assertEquals(0, service.calls)
    }

    @Test
    fun `전용 조회 API에는 PATCH 동작이 없다`() = app { service ->
        val response =
            client.patch("$path/admin") { bearerAuth(token()); contentType(ContentType.Application.Json); setBody("{}") }
        assertTrue(response.status.value in setOf(404, 405)); assertEquals(0, service.calls)
    }
}
