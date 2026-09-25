package jp.co.translacat.languagelearning.features.leveltest.api

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
import jp.co.translacat.languagelearning.features.leveltest.application.*
import jp.co.translacat.languagelearning.features.leveltest.support.*
import jp.co.translacat.languagelearning.shared.security.InternalApiSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Instant
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LevelTestRoutesTest {
    private val key = ByteArray(32) { it.toByte() }
    private val settings = InternalApiSettings(enabled = true, secretBase64 = Base64.getEncoder().encodeToString(key))
    private val root = "/internal/v1/language-learning/level-test"
    private fun token(user: Long = 123, use: String = "ll-internal"): String = JWT.create()
        .withIssuer("translacat-be")
        .withAudience("translacat-ll")
        .withSubject(user.toString())
        .withClaim("service", "translacat-be")
        .withClaim("tokenUse", use)
        .withArrayClaim("roles", arrayOf("USER"))
        .withIssuedAt(Date.from(Instant.now()))
        .withExpiresAt(Date.from(Instant.now().plusSeconds(60)))
        .sign(Algorithm.HMAC256(key))

    private fun app(block: suspend ApplicationTestBuilder.(MemoryLevelTest) -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        val work = MemoryLevelTest();
        val context = TestLevelContext();
        val ai = TestLevelAi();
        val audio = LevelAudioService(work, MemoryAudioStore(), "http://localhost")
        application {
            configureSerialization(); configureStatusPages(); configureInternalAuthentication(settings)
            routing {
                levelTestRoutes(
                    work, LevelSessionService(work, context), LevelQuestionService(work, context, ai, audio),
                    LevelAnswerService(work, context, ai, audio), LevelReadService(work, audio, ai, context), audio,
                )
            }
        }
        block(work)
    }

    @Test
    fun `인증이 없는 조회와 서비스 조회용 토큰으로는 사용자 세션을 만들지 않는다`() {
        app { work ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("$root/status").status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("$root/status") { bearerAuth(token(use = "ll-settings-service")) }.status,
            )
            assertTrue(work.repo.sessions.isEmpty())
        }
    }

    @Test
    fun `start current answer의 외부 body 계약과 정답 비노출을 확인한다`() {
        app { work ->
            val start = client.post("$root/sessions") {
                bearerAuth(token()); contentType(ContentType.Application.Json); setBody(
                """{"type":"INITIAL","idempotencyKey":"http-start"}""",
            )
            }
            assertEquals(HttpStatusCode.OK, start.status)
            val id = Json.parseToJsonElement(start.bodyAsText()).jsonObject.getValue("sessionId").jsonPrimitive.long
            val current = client.get("$root/sessions/$id/current-item") { bearerAuth(token()) }
            assertEquals(HttpStatusCode.OK, current.status)
            val body = Json.parseToJsonElement(current.bodyAsText()).jsonObject
            assertFalse("internalAnswerKey" in body); assertFalse("referencePayload" in body); assertFalse(
            "correctOptionKey" in body,
        )
            val item = body.getValue("itemId").jsonPrimitive.long
            val result = client.post("$root/sessions/$id/items/$item/answers") {
                bearerAuth(token()); contentType(
                ContentType.Application.Json,
            ); setBody("""{"selectedOptionKey":"A","idempotencyKey":"answer"}""")
            }
            assertEquals(HttpStatusCode.OK, result.status); assertEquals(
            2, work.repo.session(id)?.currentQuestionNumber,
        )
        }
    }

    @Test
    fun `다른 사용자의 세션과 미완료 결과는 404다`() {
        app { work ->
            val session = LevelSessionService(work, TestLevelContext()).start(123, null, "owned")
            assertEquals(
                HttpStatusCode.NotFound, client.get("$root/sessions/${session.id}") { bearerAuth(token(456)) }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("$root/sessions/${session.id}/result") { bearerAuth(token()) }.status,
            )
        }
    }

    @Test
    fun `알 수 없는 JSON 필드와 잘못된 업로드 capability를 거부한다`() {
        app { _ ->
            val r = client.post("$root/sessions") {
                bearerAuth(token()); contentType(ContentType.Application.Json); setBody(
                """{"type":"INITIAL","idempotencyKey":"x","userId":456}""",
            )
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertEquals(
                HttpStatusCode.NotFound,
                client.put("$root/audio-uploads/missing?token=wrong") {
                    contentType(
                        ContentType.parse("audio/wav"),
                    ); setBody(LevelFixtures.wav)
                }.status,
            )
        }
    }
}
