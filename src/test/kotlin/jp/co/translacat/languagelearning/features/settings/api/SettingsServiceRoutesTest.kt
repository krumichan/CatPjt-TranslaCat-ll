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
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningPolicy
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsResult
import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsSnapshot
import jp.co.translacat.languagelearning.features.settings.domain.model.ConfiguredLanguagePair
import jp.co.translacat.languagelearning.shared.security.InternalApiSettings
import kotlinx.serialization.json.*
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
        use: String = "ll-settings-service", scopes: List<String> = listOf("settings:read"), expired: Boolean = false,
    ): String {
        val now = Instant.now().minusSeconds(if (expired) 500 else 0)
        val builder = JWT.create().withIssuer("translacat-be").withAudience("translacat-ll")
            .withClaim("service", "translacat-be").withClaim("tokenUse", use)
            .withSubject(if (use == "ll-settings-service") "translacat-be" else "123")
            .withIssuedAt(Date.from(now)).withExpiresAt(Date.from(now.plusSeconds(120)))
        if (use == "ll-settings-service") builder.withArrayClaim("scopes", scopes.toTypedArray())
        else builder.withArrayClaim("roles", arrayOf("ADMIN"))
        return builder.sign(Algorithm.HMAC256(key))
    }

    private class Service : SettingsServiceOperations {
        var calls = 0
        var otherCalls = 0
        override suspend fun userSnapshot(userId: Long): UserSettingsSnapshot {
            otherCalls++
            return UserSettingsSnapshot(
                userId, F.now.toLocalDate(), UserSettingsResult(F.configured(userId), F.policy()),
            )
        }

        override suspend fun learningDate(userId: Long) = F.now.toLocalDate().also { otherCalls++ }
        override suspend fun adminPolicy() = F.admin().also { otherCalls++ }
        override suspend fun configuredLanguagePairs() =
            listOf(ConfiguredLanguagePair("ko", "ja")).also { otherCalls++ }

        override suspend fun listeningPolicy() = ListeningPolicy(
            enabled = true, defaultItemCount = 5, minItemCount = 1, maxItemCount = 20, hardItemLimit = 20,
            referenceAudioMaxSeconds = 30, repeatAudioMaxSeconds = 60, maxAudioFileBytes = 10485760L,
            maxRerecordCount = 3, resumeHours = 24, referenceAudioRetentionDays = 7, userAudioRetentionDays = 7,
            reportedAudioRetentionDays = 30, automaticRetryLimit = 2, manualRetryLimit = 1, practiceAttemptLimit = 3,
            profilePolicyVersion = "synthetic-profile", modelConfigVersion = "synthetic-model",
            referenceTtsRegenerationEnabled = false,
        ).also { calls++ }
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
    fun `인증 없이 현재 Listening 정책을 조회할 수 없다`() = app { service ->
        // 실행
        val response = client.get("$path/listening-policy")

        // 검증
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0, service.calls)
    }

    @Test
    fun `사용자와 관리자 용도 토큰을 서비스 토큰으로 혼용하지 않는다`() = app { service ->
        // 실행
        val response = client.get("$path/listening-policy") { bearerAuth(token("ll-internal")) }

        // 검증
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0, service.calls)
    }

    @Test
    fun `read scope만 수락하고 write 누락 중복 scope와 만료 토큰을 거부한다`() = app { service ->
        // 준비
        val rejected = listOf(
            token(scopes = listOf("settings:write")), token(scopes = emptyList()),
            token(scopes = listOf("settings:read", "settings:read")), token(expired = true),
        )

        // 실행 및 검증
        for (value in rejected) {
            assertEquals(HttpStatusCode.Unauthorized, client.get("$path/listening-policy") { bearerAuth(value) }.status)
        }
        assertEquals(0, service.calls)
    }

    @Test
    fun `전용 서비스 토큰은 Listening 정책만 읽고 사용자 헤더를 사용하지 않는다`() = app { service ->
        // 실행
        val response = client.get("$path/listening-policy") { bearerAuth(token()); header("X-User-Id", "999") }

        // 검증
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(5, body.getValue("defaultItemCount").jsonPrimitive.int)
        assertEquals("synthetic-profile", body.getValue("profilePolicyVersion").jsonPrimitive.content)
        assertEquals(false, body.getValue("referenceTtsRegenerationEnabled").jsonPrimitive.boolean)
        assertEquals(1, service.calls)
        assertEquals(0, service.otherCalls)
        assertTrue("userId" !in body && "revision" !in body)
    }

    @Test
    fun `퇴역한 사용자 날짜 관리자 언어쌍 전달 경로는 모두 없다`() = app { service ->
        // 준비
        val retired =
            listOf("admin", "users/123", "users/123/learning-date", "users/0", "users/abc", "language-pairs", "resolve")

        // 실행 및 검증
        for (suffix in retired) {
            assertEquals(HttpStatusCode.NotFound, client.get("$path/$suffix") { bearerAuth(token()) }.status)
        }
        assertEquals(0, service.calls)
        assertEquals(0, service.otherCalls)
    }

    @Test
    fun `현재 서비스 조회 API에는 PATCH 동작이 없다`() = app { service ->
        // 실행
        val response = client.patch("$path/listening-policy") {
            bearerAuth(token()); contentType(ContentType.Application.Json); setBody("{}")
        }

        // 검증
        assertTrue(response.status.value in setOf(404, 405))
        assertEquals(0, service.calls)
    }
}
