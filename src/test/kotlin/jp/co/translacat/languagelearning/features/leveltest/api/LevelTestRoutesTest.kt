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
    /** 인증 소유자의 선택만 반영하고 결과·이력 조회는 새 초기화를 만들지 않는다. */
    @Test
    fun `직접 선택은 0문항 결과와 이력으로 조회되며 다른 소유자에게 노출되지 않는다`() {
        app { work ->
            // 준비 및 실행: 같은 키를 재전송해도 최초 결과 하나만 생성한다.
            val payload = """{"band":"B2","idempotencyKey":"http-selection"}"""
            val first = client.post("$root/self-selection") {
                bearerAuth(token()); contentType(ContentType.Application.Json); setBody(payload)
            }
            assertEquals(HttpStatusCode.OK, first.status)
            val body = Json.parseToJsonElement(first.bodyAsText()).jsonObject
            val id = body.getValue("sessionId").jsonPrimitive.long
            val replay = client.post("$root/self-selection") {
                bearerAuth(token()); contentType(ContentType.Application.Json); setBody(payload)
            }

            // 검증: 시험 문항·평가 없이 본인의 저장 결과를 읽는다.
            assertEquals(first.bodyAsText(), replay.bodyAsText())
            assertEquals("SELF_SELECTED", body.getValue("sessionType").jsonPrimitive.content)
            assertEquals("0", body.getValue("totalQuestions").jsonPrimitive.content)
            assertEquals(HttpStatusCode.OK, client.get("$root/sessions/$id/result") { bearerAuth(token()) }.status)
            assertEquals(HttpStatusCode.OK, client.get("$root/history") { bearerAuth(token()) }.status)
            assertEquals(HttpStatusCode.NotFound,
                client.get("$root/sessions/$id/result") { bearerAuth(token(456)) }.status)
            assertEquals(HttpStatusCode.Conflict, client.post("$root/self-selection") {
                bearerAuth(token()); contentType(ContentType.Application.Json)
                setBody("""{"band":"B3","idempotencyKey":"overwrite"}""")
            }.status)
            assertEquals(1, work.repo.sessions.size)
            assertTrue(work.repo.items.isEmpty())
            assertTrue(work.repo.evaluations.isEmpty())
        }
    }

    /** 상위 band와 점수·소유자 주입, 인증 누락은 저장 전에 거부한다. */
    @Test
    fun `직접 선택의 권한과 엄격한 입력 계약을 확인한다`() {
        app { work ->
            // 준비 및 실행: 화면을 우회한 요청도 같은 서버 검증을 통과해야 한다.
            assertEquals(HttpStatusCode.Unauthorized, client.post("$root/self-selection") {
                contentType(ContentType.Application.Json)
                setBody("""{"band":"B1","idempotencyKey":"anonymous"}""")
            }.status)
            listOf(
                """{"band":"B4","idempotencyKey":"upper"}""",
                """{"band":"B5","idempotencyKey":"upper"}""",
                """{"band":"B2","idempotencyKey":"inject","baseLevelScore":99}""",
                """{"band":"B2","idempotencyKey":"inject","userId":456}""",
                """{"band":"B1"}""",
            ).forEach { payload ->
                assertEquals(HttpStatusCode.BadRequest, client.post("$root/self-selection") {
                    bearerAuth(token()); contentType(ContentType.Application.Json); setBody(payload)
                }.status)
            }

            // 검증: 잘못된 요청은 세션과 기준점 모두 남기지 않는다.
            assertTrue(work.repo.sessions.isEmpty())
            assertTrue(work.repo.baselines.isEmpty())
        }
    }

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
    fun `옛 Core 기준점 전달 경로 없이 현재 초기 상태를 조회한다`() {
        app { work ->
            // 준비 및 실행
            val status = client.get("$root/status") { bearerAuth(token()) }

            // 검증: 현재 초기 흐름은 유지하며 옛 동기화 wrapper는 노출하지 않는다.
            assertEquals(HttpStatusCode.OK, status.status)
            assertEquals(HttpStatusCode.NotFound, client.get("$root/baseline") { bearerAuth(token()) }.status)
            assertEquals(HttpStatusCode.NotFound, client.get("$root/completions") { bearerAuth(token()) }.status)
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
