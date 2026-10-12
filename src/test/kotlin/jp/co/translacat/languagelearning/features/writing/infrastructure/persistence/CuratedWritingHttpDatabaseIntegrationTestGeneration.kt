package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import jp.co.translacat.languagelearning.bootstrap.*
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthProfile
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningPolicy
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.application.model.*
import jp.co.translacat.languagelearning.features.settings.domain.model.ConfiguredLanguagePair
import jp.co.translacat.languagelearning.features.writing.api.*
import jp.co.translacat.languagelearning.features.writing.application.CuratedWritingGenerationService
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.*
import jp.co.translacat.languagelearning.shared.ai.*
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.shared.security.InternalApiSettings
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import jp.co.translacat.languagelearning.support.SettingsFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.*
import java.util.*
import kotlin.test.*

/** 실제 HTTP 인증·DB 저장·계획 선택을 연결하며 모델 출력만 합성한다. */
class CuratedWritingHttpDatabaseIntegrationTestGeneration {
    private val date = LocalDate.parse("2026-10-11")
    private val clock = Clock.fixed(Instant.parse("2026-10-11T03:00:00Z"), ZoneOffset.UTC)
    private val key = ByteArray(32) { (it + 5).toByte() }
    private val auth = InternalApiSettings(enabled = true, secretBase64 = Base64.getEncoder().encodeToString(key))
    private val base = "/internal/v1/language-learning/writing/curated"
    private val settings = object : SettingsServiceOperations {
        override suspend fun userSnapshot(userId: Long) = UserSettingsSnapshot(userId, date,
            UserSettingsResult(SettingsFixtures.configured(userId), SettingsFixtures.policy()))
        override suspend fun learningDate(userId: Long) = date
        override suspend fun adminPolicy() = SettingsFixtures.admin()
        override suspend fun configuredLanguagePairs() = listOf(ConfiguredLanguagePair("ko", "ja"))
        override suspend fun listeningPolicy(): ListeningPolicy = error("not used")
    }

    @Test
    fun `인증된 세 유형 후보는 실제 계획에서 선택되고 무권한 조회와 metadata 주입은 거절된다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            // 준비
            val runner = JdbcTransactionRunner(factory.database, 4)
            val manifest = CuratedWritingPlanFixtures.manifest(3)
            val snapshots = CuratedWritingStore(runner, manifest, clock)
            val store = CuratedWritingGenerationStore(runner, snapshots, manifest.releaseId, clock)
            var calls = 0
            val service = CuratedWritingGenerationService(store, ModelExecutionPort { command ->
                calls++
                val input = Json.parseToJsonElement(command.messages.single().content).jsonObject
                ModelExecutionResult(CuratedWritingGenerationPolicyTest.content(WritingType.valueOf(input.getValue("writingType").jsonPrimitive.content)),
                    120, 240, "openai", "gpt-5.6-luna")
            }, clock)
            val plans = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId, clock = clock)
            runBlocking {
                snapshots.importManifest()
                ExposedWritingSetUnitOfWork(runner, clock).write(71010) {
                    growth.saveProfile(GrowthProfile(userId = 71010, state = "ACTIVE", baseLevelScore = 60.0,
                        createdAt = nowUtc, updatedAt = nowUtc))
                }
            }
            testApplication {
                environment { config = MapApplicationConfig() }
                application {
                    configureSerialization()
                    configureStatusPages()
                    configureInternalAuthentication(auth)
                    routing { curatedWritingGenerationRoutes(service, settings); curatedWritingPlanRoutes(plans, settings, true) }
                }
                suspend fun post(path: String, body: JsonObject) = client.post(base + path) {
                    bearerAuth(token(71010)); contentType(ContentType.Application.Json); setBody(body.toString())
                }

                // 실행 / 검증: 요청별 한 번 생성하고 같은 콘텐츠를 실제 계획에 복사한다.
                for (type in WritingType.entries) {
                    val request = buildJsonObject {
                        put("requestKey", type.name); put("writingType", type.name); put("targetBand", 3); put("topicKey", "library-plan")
                    }
                    val response = post("/preparations", request)
                    assertEquals(HttpStatusCode.OK, response.status)
                    val prepared = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    assertEquals("SUCCEEDED", prepared.getValue("status").jsonPrimitive.content)
                    assertEquals(prepared, Json.parseToJsonElement(post("/preparations", request).bodyAsText()))
                    val planResponse = post("/plans", buildJsonObject {
                        put("writingType", type.name); put("targetItemCount", 1)
                        put("catalogIds", buildJsonArray { add(prepared.getValue("catalogId")) })
                    })
                    assertEquals(HttpStatusCode.OK, planResponse.status, "${type.name}: ${planResponse.bodyAsText()}")
                    val plan = Json.parseToJsonElement(planResponse.bodyAsText()).jsonObject
                    val item = plan.getValue("items").jsonArray.single().jsonObject
                    assertEquals(prepared.getValue("catalogId"), item.getValue("catalogId"))
                    assertNull(item["reference"])
                    assertTrue(item.getValue("taskRequirements").jsonArray.isNotEmpty())
                    assertEquals(HttpStatusCode.NotFound, client.get("$base/preparations/${type.name}") { bearerAuth(token(71011)) }.status)
                    assertEquals(HttpStatusCode.BadRequest, post("/preparations", JsonObject(request + ("ownerId" to JsonPrimitive(71011)))).status)
                }
                assertEquals(HttpStatusCode.Unauthorized, client.get("$base/preparations/FREE").status)
                assertEquals(3, calls)

                // 실행·검증: 명시적인 1회 국소 수정도 같은 HTTP 권한/멱등성 계약을 사용한다.
                val repair = buildJsonObject {
                    put("requestKey", "FREE-repair"); put("writingType", "FREE"); put("targetBand", 3)
                    put("topicKey", "library-plan"); put("repairOfRequestKey", "FREE")
                    put("repairNotes", "한국어 안내와 예시 설명만 수정하세요.")
                }
                val repaired = post("/preparations", repair)
                assertEquals(HttpStatusCode.OK, repaired.status)
                assertEquals("SUCCEEDED", Json.parseToJsonElement(repaired.bodyAsText()).jsonObject["status"]?.jsonPrimitive?.content)
                assertEquals(repaired.bodyAsText(), post("/preparations", repair).bodyAsText())
                assertEquals(HttpStatusCode.BadRequest, post("/preparations", JsonObject(repair - "repairNotes")).status)
                assertEquals(HttpStatusCode.Conflict, post("/preparations", JsonObject(repair + ("requestKey" to JsonPrimitive("second")))).status)
                assertEquals(HttpStatusCode.NotFound, client.post("$base/preparations") {
                    bearerAuth(token(71011)); contentType(ContentType.Application.Json); setBody(repair.toString())
                }.status)
                assertEquals(4, calls)
            }
        }
    }

    private fun token(user: Long): String {
        val now = Instant.now()
        return JWT.create().withIssuer("translacat-be").withAudience("translacat-ll")
            .withSubject(user.toString()).withClaim("service", "translacat-be")
            .withClaim("tokenUse", "ll-internal").withArrayClaim("roles", arrayOf("USER"))
            .withIssuedAt(Date.from(now)).withExpiresAt(Date.from(now.plusSeconds(120))).sign(Algorithm.HMAC256(key))
    }
}
