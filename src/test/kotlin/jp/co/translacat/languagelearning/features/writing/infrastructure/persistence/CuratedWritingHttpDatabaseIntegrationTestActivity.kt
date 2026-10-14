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
import jp.co.translacat.languagelearning.features.growth.api.growthRoutes
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthProfile
import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.ExposedGrowthUnitOfWork
import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.table.*
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningPolicy
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.application.model.*
import jp.co.translacat.languagelearning.features.settings.domain.model.ConfiguredLanguagePair
import jp.co.translacat.languagelearning.features.writing.api.curatedWritingPlanRoutes
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingPlanFixtures
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.*
import jp.co.translacat.languagelearning.shared.ai.*
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.shared.security.InternalApiSettings
import jp.co.translacat.languagelearning.support.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.*
import java.util.*
import kotlin.test.*

/** 실제 HTTP 인증/DB/worker를 사용한다. 모델 응답과 두 실패만 합성하며 외부 호출은 없다. */
class CuratedWritingHttpDatabaseIntegrationTestActivity {
    private val date = LocalDate.parse("2026-10-11")
    private val clock = Clock.fixed(Instant.parse("2026-10-10T15:05:00Z"), ZoneOffset.UTC)
    private val key = ByteArray(32) { it.toByte() }
    private val auth = InternalApiSettings(enabled = true, secretBase64 = Base64.getEncoder().encodeToString(key))
    private val root = "/internal/v1/language-learning/writing/curated/plans"
    private val growthPath = "/internal/v1/language-learning/growth/activities?source=WRITING&from=$date&to=$date"
    private fun token(user: Long): String {
        val now = Instant.now()
        return JWT.create().withIssuer("translacat-be").withAudience("translacat-ll")
            .withSubject(user.toString()).withClaim("service", "translacat-be").withClaim("tokenUse", "ll-internal")
            .withArrayClaim("roles", arrayOf("USER")).withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(120))).sign(Algorithm.HMAC256(key))
    }
    private val settings = object : SettingsServiceOperations {
        override suspend fun userSnapshot(userId: Long) = UserSettingsSnapshot(userId, date,
            UserSettingsResult(SettingsFixtures.configured(userId), SettingsFixtures.policy()))
        override suspend fun learningDate(userId: Long) = date
        override suspend fun adminPolicy() = SettingsFixtures.admin()
        override suspend fun configuredLanguagePairs() = listOf(ConfiguredLanguagePair("ko", "ja"))
        override suspend fun listeningPolicy(): ListeningPolicy = error("not used")
    }

    @Test
    fun `한 계획18답안의16성공2실패와 재시도는활동18대30을유지하며 삭제조회와권한을지킨다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            // 준비: 단일 30문항 계획과 실패2회가 있는 비점수 worker.
            val runner = JdbcTransactionRunner(factory.database, 4)
            val manifest = CuratedWritingPlanFixtures.manifest(30)
            val snapshots = CuratedWritingStore(runner, manifest, clock)
            val failedAnswers = setOf("学習回答7です。", "学習回答14です。")
            var calls = 0
            val model = ModelExecutionPort { command ->
                calls++
                val input = Json.parseToJsonElement(command.messages.single().content).jsonObject
                val answer = CuratedWritingFeedbackTestPayload.answer(input)
                val failed = answer in failedAnswers &&
                    (command.traceId.endsWith("-1") || command.traceId.endsWith("-1-repair"))
                ModelExecutionResult(CuratedWritingFeedbackTestPayload.v12Response(command, invalid = failed),
                    100, 50, "openai", "gpt-5-mini")
            }
            val worker = CuratedWritingFeedbackWorker(runner, snapshots, manifest.releaseId, model, clock)
            val plans = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId, clock = clock, feedback = worker)
            runBlocking {
                snapshots.importManifest()
                ExposedWritingSetUnitOfWork(runner, clock).write(9601) {
                    val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                    growth.saveProfile(GrowthProfile(userId = 9601, state = "ACTIVE", baseLevelScore = 60.0,
                        createdAt = now, updatedAt = now))
                }
            }
            testApplication {
                environment { config = MapApplicationConfig() }
                application {
                    configureSerialization()
                    configureStatusPages()
                    configureInternalAuthentication(auth)
                    routing {
                        curatedWritingPlanRoutes(plans, settings, true)
                        growthRoutes(ExposedGrowthUnitOfWork(runner, clock))
                    }
                }
                suspend fun post(path: String, body: String, user: Long = 9601) = client.post(root + path) {
                    bearerAuth(token(user)); contentType(ContentType.Application.Json); setBody(body)
                }
                suspend fun getActivities(user: Long = 9601) = Json.parseToJsonElement(client.get(growthPath) {
                    bearerAuth(token(user))
                }.bodyAsText()).jsonObject
                suspend fun checkedPost(path: String, body: String): JsonObject {
                    val response = post(path, body)
                    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                    return Json.parseToJsonElement(response.bodyAsText()).jsonObject
                }
                assertEquals(HttpStatusCode.Unauthorized, client.get(growthPath).status)
                val initial = checkedPost("", """{"writingType":"FREE","targetItemCount":10}""")
                val setId = initial.getValue("dailySetId").jsonPrimitive.content
                assertTrue(getActivities().getValue("activities").jsonArray.isEmpty())
                val expanded = checkedPost("/$setId/target", """{"targetItemCount":30,"planRevision":1}""")
                var submitted = expanded

                // 실행: 현재 목표30에서 18개만 접수한다. 모델 실패도 활동 접수를 되돌리지 않는다.
                expanded.getValue("items").jsonArray.take(18).forEachIndexed { index, element ->
                    val item = element.jsonObject
                    val answer = buildJsonObject {
                        put("answer", "学習回答${index + 1}です。")
                        put("contentRevision", item.getValue("contentRevision"))
                    }.toString()
                    submitted = checkedPost("/items/${item.getValue("itemId").jsonPrimitive.content}/answers", answer)
                }
                assertEquals(18, submitted.getValue("submittedItemCount").jsonPrimitive.int)
                assertEquals(16, submitted.getValue("feedbackCompletedItemCount").jsonPrimitive.int)
                assertEquals("IN_PROGRESS", submitted.getValue("activity").jsonObject.getValue("status").jsonPrimitive.content)
                val before = getActivities()
                val activity = before.getValue("activities").jsonArray.single().jsonObject
                assertEquals(0, activity.getValue("durationSeconds").jsonPrimitive.int)
                assertEquals(JsonNull, activity.getValue("overallScore"))
                assertTrue(activity.getValue("metrics").jsonArray.isEmpty())
                assertEquals(18, Json.parseToJsonElement(activity.getValue("metadataJson").jsonPrimitive.content)
                    .jsonObject.getValue("submittedItemCount").jsonPrimitive.int)
                val failed = submitted.getValue("items").jsonArray.flatMap { it.jsonObject.getValue("attempts").jsonArray }
                    .map { it.jsonObject }.filter { it.getValue("feedback").jsonObject.getValue("status").jsonPrimitive.content == "FAILED" }
                assertEquals(2, failed.size)
                failed.forEach { checkedPost("/answers/${it.getValue("answerId").jsonPrimitive.content}/feedback/retry", "{}") }

                // 검증: retry/GET은 활동 ID·revision·18/30을 바꾸지 않으며 타인에게 노출하지 않는다.
                assertEquals(38, calls)
                assertEquals(before, getActivities())
                assertTrue(getActivities(9602).getValue("activities").jsonArray.isEmpty())
                assertEquals(HttpStatusCode.NotFound, post("/$setId/delete", """{"planRevision":2}""", 9602).status)
                assertEquals(HttpStatusCode.Conflict, post("/$setId/delete", """{"planRevision":1}""").status)
                checkedPost("/$setId/delete", """{"planRevision":2}""")
                assertTrue(getActivities().getValue("activities").jsonArray.isEmpty())
                assertEquals(HttpStatusCode.NotFound, post("/answers/${failed.first().getValue("answerId").jsonPrimitive.content}/feedback/retry", "{}").status)
                assertEquals(38, calls)
                runner.read {
                    assertEquals(0L, CuratedAnswersTable.selectAll().count())
                    assertEquals(0L, CuratedWritingFeedbackTable.selectAll().count())
                    assertEquals(0L, GrowthActivitiesTable.selectAll().count())
                    assertEquals(0L, GrowthMetricsTable.selectAll().count())
                    assertEquals(0L, WritingEvaluationsTable.selectAll().count())
                }
            }
        }
    }
}
