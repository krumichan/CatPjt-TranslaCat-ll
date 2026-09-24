package jp.co.translacat.languagelearning.features.resultjournal.api

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
import jp.co.translacat.languagelearning.features.resultjournal.api.dto.ResultEnvelopeDto
import jp.co.translacat.languagelearning.features.resultjournal.application.AcceptLearningResult
import jp.co.translacat.languagelearning.shared.security.InternalApiSettings
import jp.co.translacat.languagelearning.support.MemoryResultJournal
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import jp.co.translacat.languagelearning.support.ResultJournalFixtures as F

class ResultJournalRoutesTest {
    private val key = ByteArray(32) { it.toByte() }
    private val settings = InternalApiSettings(enabled = true, secretBase64 = Base64.getEncoder().encodeToString(key))
    private val path = "/internal/v1/learning-results"
    private fun token(
        use: String = "ll-learning-results-v1", scope: String = "learning-results:write", admin: Boolean = false
    ): String {
        val now = Instant.now()
        val builder = JWT.create()
            .withIssuer(settings.issuer)
            .withAudience(settings.audience)
            .withSubject(settings.callerService)
            .withClaim("service", settings.callerService)
            .withClaim("tokenUse", use)
            .withArrayClaim("scopes", arrayOf(scope))
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(60)))
        if (admin) builder.withArrayClaim("roles", arrayOf("ADMIN"))
        return builder.sign(Algorithm.HMAC256(key))
    }

    private fun envelope(payload: String = F.PAYLOAD): ResultEnvelopeDto {
        val e = F.event(payload = payload)
        return ResultEnvelopeDto(
            e.schemaVersion,
            e.sourceInstanceId,
            e.eventId,
            e.userId,
            e.sequence,
            e.kind.name,
            e.referenceId,
            e.occurredAt,
            e.payloadJson,
            e.payloadSha256
        )
    }

    @Test
    fun `인증 없는 요청과 다른 용도의 토큰은 저장하지 않는다`() {
        testApplication {
            environment { config = MapApplicationConfig() }
            val db = MemoryResultJournal()
            application {
                configureSerialization(); configureStatusPages(); configureInternalAuthentication(settings)
                routing { resultJournalRoutes(AcceptLearningResult(db, F.SOURCE)) }
            }
            assertEquals(HttpStatusCode.Unauthorized, client.post(path).status)
            for (t in listOf(token("ll-internal"), token(scope = "settings:read"), token(admin = true))) {
                assertEquals(HttpStatusCode.Unauthorized, client.post(path) {
                    bearerAuth(t); contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(envelope()))
                }.status)
            }
            assertTrue(db.events.isEmpty())
        }
    }

    @Test
    fun `정상 요청 중복 재전달 순번 충돌을 HTTP로 구분한다`() {
        testApplication {
            environment { config = MapApplicationConfig() }
            val db = MemoryResultJournal()
            application {
                configureSerialization(); configureStatusPages(); configureInternalAuthentication(settings)
                routing { resultJournalRoutes(AcceptLearningResult(db, F.SOURCE)) }
            }
            val e = envelope();
            val json = Json.encodeToString(e)
            val first =
                client.post(path) { bearerAuth(token()); contentType(ContentType.Application.Json); setBody(json) }
            assertEquals(HttpStatusCode.OK, first.status); assertTrue(first.bodyAsText().contains("RECORDED"))
            val duplicate =
                client.post(path) { bearerAuth(token()); contentType(ContentType.Application.Json); setBody(json) }
            assertTrue(duplicate.bodyAsText().contains("DUPLICATE"))
            assertEquals(HttpStatusCode.Conflict, client.post(path) {
                bearerAuth(token()); contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(envelope().copy(sequence = 3)))
            }.status)
            assertEquals(1, db.events.size)
        }
    }

    @Test
    fun `FREE 코칭 및 해시 불일치는 점수 원장에 들어가지 않는다`() {
        testApplication {
            environment { config = MapApplicationConfig() }
            val db = MemoryResultJournal()
            application {
                configureSerialization(); configureStatusPages(); configureInternalAuthentication(settings)
                routing { resultJournalRoutes(AcceptLearningResult(db, F.SOURCE)) }
            }
            for (e in listOf(
                envelope(F.PAYLOAD.replace("SCORED_EVALUATION", "SESSION_COACHING")),
                envelope().copy(payloadSha256 = "0".repeat(64))
            )) {
                assertEquals(HttpStatusCode.BadRequest, client.post(path) {
                    bearerAuth(token()); contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(e))
                }.status)
            }
            assertEquals(0, db.transactions)
        }
    }

    @Test
    fun `알 수 없는 외부 필드와 잘못된 JSON을 거부한다`() {
        testApplication {
            environment { config = MapApplicationConfig() }
            val db = MemoryResultJournal()
            application {
                configureSerialization(); configureStatusPages(); configureInternalAuthentication(settings)
                routing { resultJournalRoutes(AcceptLearningResult(db, F.SOURCE)) }
            }
            for (body in listOf("{broken", Json.encodeToString(envelope()).dropLast(1) + ",\"admin\":true}")) {
                assertEquals(
                    HttpStatusCode.BadRequest,
                    client.post(path) { bearerAuth(token()); contentType(ContentType.Application.Json); setBody(body) }.status
                )
            }
            assertTrue(db.events.isEmpty())
        }
    }

    @Test
    fun `최대 요청 크기를 넘으면 DB 접근 전에 거부한다`() {
        testApplication {
            environment { config = MapApplicationConfig() }
            val db = MemoryResultJournal()
            application {
                configureSerialization(); configureStatusPages(); configureInternalAuthentication(settings)
                routing { resultJournalRoutes(AcceptLearningResult(db, F.SOURCE)) }
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, client.post(path) {
                bearerAuth(token()); contentType(ContentType.Application.Json)
                setBody(" ".repeat(2_097_153))
            }.status)
            assertEquals(0, db.transactions)
        }
    }

    @Test
    fun `서명된 이벤트의 원본 식별자도 환경과 일치해야 한다`() {
        testApplication {
            environment { config = MapApplicationConfig() }
            val db = MemoryResultJournal()
            application {
                configureSerialization(); configureStatusPages(); configureInternalAuthentication(settings)
                routing { resultJournalRoutes(AcceptLearningResult(db, F.SOURCE)) }
            }
            assertEquals(HttpStatusCode.Conflict, client.post(path) {
                bearerAuth(token()); contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(envelope().copy(sourceInstanceId = "7a8abfea-a0d1-4458-95e8-66cb5e68d9a0")))
            }.status)
            assertEquals(0, db.transactions)
        }
    }
}
