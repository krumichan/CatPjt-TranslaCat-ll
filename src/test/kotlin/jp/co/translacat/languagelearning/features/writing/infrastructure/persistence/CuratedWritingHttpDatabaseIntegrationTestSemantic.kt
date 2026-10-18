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
import jp.co.translacat.languagelearning.shared.ai.*
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
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

/** 인증 HTTP와 실제 DB의 V13 경로다. 합성 Provider를 실제 OpenAI 검사로 취급하지 않는다. */
class CuratedWritingHttpDatabaseIntegrationTestSemantic {
    private val date = LocalDate.parse("2026-10-17")
    private val clock = Clock.fixed(Instant.parse("2026-10-17T03:00:00Z"), ZoneOffset.UTC)
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val auth = InternalApiSettings(enabled = true, secretBase64 = Base64.getEncoder().encodeToString(key))
    private val root = "/internal/v1/language-learning/writing/curated/plans"
    private val settings = object : SettingsServiceOperations {
        override suspend fun userSnapshot(userId: Long) = UserSettingsSnapshot(userId, date,
            UserSettingsResult(SettingsFixtures.configured(userId), SettingsFixtures.policy()))
        override suspend fun learningDate(userId: Long) = date
        override suspend fun adminPolicy() = SettingsFixtures.admin()
        override suspend fun configuredLanguagePairs() = listOf(ConfiguredLanguagePair("ko", "ja"))
        override suspend fun listeningPolicy(): ListeningPolicy = error("not used")
    }

    private fun token(userId: Long): String {
        val issued = Instant.now()
        return JWT.create().withIssuer("translacat-be").withAudience("translacat-ll")
            .withSubject(userId.toString()).withClaim("service", "translacat-be")
            .withClaim("tokenUse", "ll-internal").withArrayClaim("roles", arrayOf("USER"))
            .withIssuedAt(Date.from(issued)).withExpiresAt(Date.from(issued.plusSeconds(120)))
            .sign(Algorithm.HMAC256(key))
    }

    @Test
    fun `인증 제출은 고정 의미 계약을 저장하고 조회 중복 소유권 revision 경계를 보존한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            // 준비: 현재 공개 과제와 합성 owner, 실제 인증 라우트를 연결한다.
            val runner = JdbcTransactionRunner(factory.database, 4)
            val manifest = CuratedWritingSemanticTestModel.manifest()
            val snapshots = CuratedWritingStore(runner, manifest, clock)
            var calls = 0
            val model = ModelExecutionPort { command ->
                calls++
                ModelExecutionResult(CuratedWritingSemanticTestModel.response(command), 10, 5, "openai", "gpt-5-mini")
            }
            val feedback = CuratedWritingFeedbackWorker(runner, snapshots, manifest.releaseId, model, clock)
            val plans = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId, clock = clock, feedback = feedback)
            val user = 9414L
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
                val startResponse = post("", buildJsonObject { put("writingType", "TRANSLATION"); put("targetItemCount", 1) })
                assertEquals(HttpStatusCode.OK, startResponse.status, startResponse.bodyAsText())
                val started = Json.parseToJsonElement(startResponse.bodyAsText()).jsonObject
                val item = started.getValue("items").jsonArray.single().jsonObject
                val itemId = item.getValue("itemId").jsonPrimitive.content
                val setId = started.getValue("dailySetId").jsonPrimitive.content
                val submittedBody = buildJsonObject {
                    put("answer", CuratedWritingSemanticTestModel.answer)
                    put("contentRevision", item.getValue("contentRevision"))
                }
                CuratedWritingLegacySemanticSeed.pending(runner, snapshots,
                    CuratedWritingStore.Start(user, date, jp.co.translacat.languagelearning.features.writing.domain.model.WritingType.TRANSLATION,
                        "ko", "ja", false, true, 7), LearningPublicId.decode(itemId),
                    CuratedWritingSemanticTestModel.answer, item.getValue("contentRevision").jsonPrimitive.content, clock)
                assertEquals(0, calls)

                // 실행: 이미 저장된 V13 PENDING 작업을 인증 HTTP로 실행하며 과거 세 단계 계약을 보존한다.
                val response = post("/items/$itemId/answers", submittedBody)
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                val saved = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val attempt = saved.getValue("items").jsonArray.single().jsonObject.getValue("attempts").jsonArray.single().jsonObject
                assertEquals("SUCCEEDED", attempt.getValue("feedback").jsonObject.getValue("status").jsonPrimitive.content)
                assertEquals("curated-writing-feedback-v13", attempt.getValue("feedback").jsonObject.getValue("result")
                    .jsonObject.getValue("policyVersion").jsonPrimitive.content)
                assertEquals(JsonNull, attempt.getValue("evaluation"))
                assertEquals(3, calls)

                // 검증: 읽기·동일 답안은 그대로 반환하고 타인·오래된 revision은 거절한다.
                assertEquals(saved, Json.parseToJsonElement(get("/$setId").bodyAsText()))
                assertEquals(saved, Json.parseToJsonElement(post("/items/$itemId/answers", submittedBody).bodyAsText()))
                assertEquals(HttpStatusCode.NotFound, get("/$setId", user + 1).status)
                assertEquals(HttpStatusCode.Conflict, post("/items/$itemId/answers",
                    JsonObject(submittedBody + ("contentRevision" to JsonPrimitive("0".repeat(64))))).status)
                assertEquals(3, calls)
            }
        }
    }
}
