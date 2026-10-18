package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import jp.co.translacat.languagelearning.bootstrap.configureInternalAuthentication
import jp.co.translacat.languagelearning.bootstrap.configureSerialization
import jp.co.translacat.languagelearning.bootstrap.configureStatusPages
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthProfile
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningPolicy
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsResult
import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsSnapshot
import jp.co.translacat.languagelearning.features.settings.domain.model.ConfiguredLanguagePair
import jp.co.translacat.languagelearning.features.writing.api.curatedWritingPlanRoutes
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

/** 실제 인증 HTTP·MySQL과 합성 모델을 연결한다. 실제 OpenAI나 브라우저 E2E가 아니다. */
class CuratedWritingHttpDatabaseIntegrationTestServerRepair {
    @Test
    fun `HTTP 핵심 성공과 선택 검수 실패는 별도 상태로 저장되고 조회는 모델을 호출하지 않는다`() = exercise(
        CuratedWritingSemanticV14TestModel(optionalMode = CuratedWritingSemanticV14TestModel.OptionalMode.REJECT),
        free = false, answer = CuratedWritingSemanticTestModel.answer, expectedCalls = 4,
        expectedFeedback = "SUCCEEDED", expectedOptional = "FAILED",
    )

    @Test
    fun `HTTP 핵심 수리 반복실패는 성공 결과나 선택 제안으로 변환하지 않는다`() = exercise(
        CuratedWritingSemanticV14TestModel(coreMode = CuratedWritingSemanticV14TestModel.CoreMode.FAIL),
        free = true, answer = CuratedWritingSemanticV14TestModel.freeOneError, expectedCalls = 4,
        expectedFeedback = "FAILED", expectedOptional = null,
    )

    @Test
    fun `HTTP 전체문장이 핵심 교정 대상이면 선택 단계를 호출하지 않는다`() = exercise(
        CuratedWritingSemanticV14TestModel(), free = true,
        answer = "明日の午後、仕事が終わるので商品を取り置きしていただけるますか。", expectedCalls = 4,
        expectedFeedback = "SUCCEEDED", expectedOptional = "NOT_APPLICABLE",
    )

    @Test
    fun `HTTP 필수 전체문맥 검수 실패는 선택 실패가 아니라 결과 없는 전체 실패다`() = exercise(
        CuratedWritingSemanticV14TestModel(rejectCoreVerification = true),
        free = true, answer = CuratedWritingSemanticV14TestModel.freeOneError, expectedCalls = 4,
        expectedFeedback = "FAILED", expectedOptional = null,
    )

    private fun exercise(model: CuratedWritingSemanticV14TestModel, free: Boolean, answer: String,
                         expectedCalls: Int, expectedFeedback: String, expectedOptional: String?) = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            // 준비: 각 테스트 소유 UUID catalog와 현재 실제 인증 라우트만 사용한다.
            val runner = JdbcTransactionRunner(factory.database, 4)
            val manifest = CuratedWritingSemanticV14TestModel.manifest(free)
            val snapshots = CuratedWritingStore(runner, manifest, clock)
            val worker = CuratedWritingFeedbackWorker(runner, snapshots, manifest.releaseId, model, clock)
            val plans = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId, clock = clock, feedback = worker)
            val user = 9514L
            runBlocking {
                snapshots.importManifest()
                ExposedWritingSetUnitOfWork(runner, clock).write(user) {
                    val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                    growth.saveProfile(GrowthProfile(userId = user, state = "ACTIVE", baseLevelScore = 60.0,
                        createdAt = now, updatedAt = now))
                }
            }
            testApplication {
                environment { config = MapApplicationConfig() }
                application {
                    configureSerialization(); configureStatusPages(); configureInternalAuthentication(auth)
                    routing { curatedWritingPlanRoutes(plans, settings, true) }
                }
                suspend fun post(path: String, body: JsonObject, owner: Long = user) = client.post(root + path) {
                    bearerAuth(token(owner)); contentType(ContentType.Application.Json); setBody(body.toString())
                }
                suspend fun get(path: String, owner: Long = user) = client.get(root + path) { bearerAuth(token(owner)) }
                assertEquals(HttpStatusCode.Unauthorized, client.get("$root/config").status)
                val startedResponse = post("", buildJsonObject {
                    put("writingType", if (free) "FREE" else "TRANSLATION"); put("targetItemCount", 1)
                })
                assertEquals(HttpStatusCode.OK, startedResponse.status, startedResponse.bodyAsText())
                val started = Json.parseToJsonElement(startedResponse.bodyAsText()).jsonObject
                val item = started.getValue("items").jsonArray.single().jsonObject
                val setId = started.string("dailySetId")
                val itemId = item.string("itemId")
                val body = buildJsonObject { put("answer", answer); put("contentRevision", item.getValue("contentRevision")) }

                // 실행: 실제 답안 저장·V14 단계·직렬화된 실패/성공 상태를 HTTP로 받는다.
                val response = post("/items/$itemId/answers", body)
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                val saved = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val attempt = saved.getValue("items").jsonArray.single().jsonObject.getValue("attempts").jsonArray.single().jsonObject
                val feedback = attempt.getValue("feedback").jsonObject
                assertEquals(expectedFeedback, feedback.string("status"))
                assertEquals(JsonNull, attempt.getValue("evaluation"))
                if (expectedOptional != null) {
                    val result = feedback.getValue("result").jsonObject
                    assertEquals("curated-writing-feedback-v14", result.string("policyVersion"))
                    assertEquals("server-repair-plan-v1", result.string("repairPlanVersion"))
                    assertTrue(result.string("repairPlanHash").matches(Regex("[a-f0-9]{64}")))
                    assertEquals(expectedOptional, result.string("optionalSuggestionsStatus"))
                    if (expectedOptional == "FAILED") assertEquals("OPTIONAL_SEMANTIC_REJECTED", result.string("optionalSuggestionsFailureCode"))
                    else assertEquals(JsonNull, result.getValue("optionalSuggestionsFailureCode"))
                } else {
                    assertEquals("FEEDBACK_VALIDATION_FAILED", feedback.string("failureCode"))
                    assertEquals(JsonNull, feedback.getValue("result"))
                }

                // 검증: 저장 결과 조회·중복·타인·revision 경계가 추가 모델 호출 없이 유지된다.
                assertEquals(expectedCalls, model.commands.size)
                assertEquals(saved, Json.parseToJsonElement(get("/$setId").bodyAsText()))
                assertEquals(saved, Json.parseToJsonElement(post("/items/$itemId/answers", body).bodyAsText()))
                assertEquals(HttpStatusCode.NotFound, get("/$setId", user + 1).status)
                assertEquals(HttpStatusCode.Conflict, post("/items/$itemId/answers",
                    JsonObject(body + ("contentRevision" to JsonPrimitive("0".repeat(64))))).status)
                assertEquals(expectedCalls, model.commands.size)
            }
        }
    }

    private fun token(userId: Long): String {
        val issued = Instant.now()
        return JWT.create().withIssuer("translacat-be").withAudience("translacat-ll")
            .withSubject(userId.toString()).withClaim("service", "translacat-be")
            .withClaim("tokenUse", "ll-internal").withArrayClaim("roles", arrayOf("USER"))
            .withIssuedAt(Date.from(issued)).withExpiresAt(Date.from(issued.plusSeconds(120))).sign(Algorithm.HMAC256(key))
    }

    companion object {
        private val date = LocalDate.parse("2026-10-18")
        private val clock = Clock.fixed(Instant.parse("2026-10-18T03:00:00Z"), ZoneOffset.UTC)
        private val key = ByteArray(32) { (it + 1).toByte() }
        private val auth = InternalApiSettings(enabled = true, secretBase64 = Base64.getEncoder().encodeToString(key))
        private const val root = "/internal/v1/language-learning/writing/curated/plans"
        private val settings = object : SettingsServiceOperations {
            override suspend fun userSnapshot(userId: Long) = UserSettingsSnapshot(userId, date,
                UserSettingsResult(SettingsFixtures.configured(userId), SettingsFixtures.policy()))
            override suspend fun learningDate(userId: Long) = date
            override suspend fun adminPolicy() = SettingsFixtures.admin()
            override suspend fun configuredLanguagePairs() = listOf(ConfiguredLanguagePair("ko", "ja"))
            override suspend fun listeningPolicy(): ListeningPolicy = error("not used")
        }
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
