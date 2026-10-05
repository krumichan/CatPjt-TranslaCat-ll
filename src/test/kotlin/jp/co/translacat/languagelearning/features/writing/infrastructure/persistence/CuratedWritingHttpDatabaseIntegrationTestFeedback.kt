package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

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
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthProfile
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningPolicy
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsResult
import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsSnapshot
import jp.co.translacat.languagelearning.features.settings.domain.model.ConfiguredLanguagePair
import jp.co.translacat.languagelearning.features.writing.api.curatedWritingPlanRoutes
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingPlanFixtures
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

/** 인증된 Ktor HTTP와 실제 scratch MySQL. 모델 응답만 합성한다. */
class CuratedWritingHttpDatabaseIntegrationTestFeedback {
    private val date = LocalDate.parse("2026-10-04")
    private val clock = Clock.fixed(Instant.parse("2026-10-04T03:00:00Z"), ZoneOffset.UTC)
    private val key = ByteArray(32) { it.toByte() }
    private val auth = InternalApiSettings(enabled = true, secretBase64 = Base64.getEncoder().encodeToString(key))
    private val root = "/internal/v1/language-learning/writing/curated/plans"
    private fun token(user: Long): String {
        val issued = Instant.now()
        return JWT.create().withIssuer("translacat-be").withAudience("translacat-ll")
            .withSubject(user.toString()).withClaim("service", "translacat-be")
            .withClaim("tokenUse", "ll-internal").withArrayClaim("roles", arrayOf("USER"))
            .withIssuedAt(Date.from(issued)).withExpiresAt(Date.from(issued.plusSeconds(120)))
            .sign(Algorithm.HMAC256(key))
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
    fun `인증 HTTP 제출 조회 실패 재시도는 답안 하나에만 호출을 추가한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            val runner = JdbcTransactionRunner(factory.database, 4)
            val manifest = CuratedWritingPlanFixtures.manifest(3)
            val snapshots = CuratedWritingStore(runner, manifest, clock)
            var calls = 0
            val model = ModelExecutionPort { command ->
                calls++
                val input = Json.parseToJsonElement(command.messages.single().content).jsonObject
                val requirements = input.getValue("requirements").jsonArray
                ModelExecutionResult(buildJsonObject {
                    put("observations", buildJsonArray {
                        if (calls > 1) requirements.forEach { requirement -> add(buildJsonObject {
                            put("requirementId", requirement.jsonObject.getValue("id"))
                            put("status", "MET")
                            put("segmentIds", buildJsonArray { add("S1") })
                            put("originText", "요구 내용을 전달했습니다.")
                            put("learningText", "必要な内容を伝えています。")
                        }) }
                    })
                    put("necessaryCorrections", buildJsonArray {})
                    put("optionalAlternatives", buildJsonArray {})
                    put("uncertainties", buildJsonArray {})
                }, 100, 50, "openai", "gpt-5-mini")
            }
            val feedback = CuratedWritingFeedbackWorker(runner, snapshots, manifest.releaseId, model, clock)
            val plans = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId, clock = clock, feedback = feedback)
            runBlocking {
                snapshots.importManifest()
                ExposedWritingSetUnitOfWork(runner, clock).write(9102L) {
                    val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                    growth.saveProfile(GrowthProfile(userId = 9102L, state = "ACTIVE", baseLevelScore = 60.0,
                        createdAt = now, updatedAt = now))
                }
            }
            testApplication {
                environment { config = MapApplicationConfig() }
                application {
                    configureSerialization(); configureStatusPages(); configureInternalAuthentication(auth)
                    routing { curatedWritingPlanRoutes(plans, settings, true) }
                }
                suspend fun post(path: String, body: String, user: Long = 9102L) = client.post(root + path) {
                    bearerAuth(token(user)); contentType(ContentType.Application.Json); setBody(body)
                }
                suspend fun get(path: String, user: Long = 9102L) = client.get(root + path) { bearerAuth(token(user)) }

                // 준비·실행: 인증된 owner만 시작과 제출을 수행한다.
                assertEquals(HttpStatusCode.Unauthorized, client.get("$root/config").status)
                val started = Json.parseToJsonElement(post("", """{"writingType":"TRANSLATION","targetItemCount":1}""").bodyAsText()).jsonObject
                val setId = started.getValue("dailySetId").jsonPrimitive.content
                val item = started.getValue("items").jsonArray.single().jsonObject
                val itemId = item.getValue("itemId").jsonPrimitive.content
                val revision = item.getValue("contentRevision").jsonPrimitive.content
                assertFalse(started.toString().contains("referenceAnswers"))
                val submitted = Json.parseToJsonElement(post("/items/$itemId/answers",
                    """{"answer":"欠席した生徒も増えていました。","contentRevision":"$revision"}""").bodyAsText()).jsonObject
                val attempt = submitted.getValue("items").jsonArray.single().jsonObject.getValue("attempts").jsonArray.single().jsonObject
                val answerId = attempt.getValue("answerId").jsonPrimitive.content

                // 검증: 첫 실패 상태, GET과 중복 제출의 무호출, 타인 접근 거절.
                assertEquals("FAILED", attempt.getValue("feedback").jsonObject.getValue("status").jsonPrimitive.content)
                assertEquals(1, calls)
                assertEquals(HttpStatusCode.NotFound, get("/$setId", 9103L).status)
                assertEquals(HttpStatusCode.NotFound, post("/answers/$answerId/feedback/retry", "{}", 9103L).status)
                assertEquals(HttpStatusCode.OK, get("/$setId").status)
                assertEquals(HttpStatusCode.OK, post("/items/$itemId/answers",
                    """{"answer":"欠席した生徒も増えていました。","contentRevision":"$revision"}""").status)
                assertEquals(1, calls)

                // 실행·검증: 실패 답안에 대한 명시적 retry만 두 번째 호출을 만든다.
                val retried = post("/answers/$answerId/feedback/retry", "{}")
                assertEquals(HttpStatusCode.OK, retried.status, retried.bodyAsText())
                val after = Json.parseToJsonElement(retried.bodyAsText()).jsonObject
                assertEquals(1, after.getValue("feedbackCompletedItemCount").jsonPrimitive.int)
                assertEquals(2, calls)
                assertEquals(after, Json.parseToJsonElement(get("/$setId").bodyAsText()))
                assertEquals(2, calls)
            }
        }
    }

    @Test
    fun `합성 모델 장애 두 건만 복구하고 열여덟 답안과 정상 피드백은 보존한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            val runner = JdbcTransactionRunner(factory.database, 4)
            val manifest = CuratedWritingPlanFixtures.manifest(3)
            val snapshots = CuratedWritingStore(runner, manifest, clock)
            val failures = setOf("学習用回答7です。", "学習用回答14です。")
            val failedOnce = mutableSetOf<String>()
            var calls = 0
            val model = ModelExecutionPort { command ->
                calls++
                val input = Json.parseToJsonElement(command.messages.single().content).jsonObject
                val answer = input.getValue("answer").jsonPrimitive.content
                val requirements = input.getValue("requirements").jsonArray
                val inject = answer in failures && failedOnce.add(answer)
                ModelExecutionResult(buildJsonObject {
                    put("observations", buildJsonArray {
                        if (!inject) requirements.forEach { requirement -> add(buildJsonObject {
                            put("requirementId", requirement.jsonObject.getValue("id"))
                            put("status", "MET")
                            put("segmentIds", buildJsonArray { add("S1") })
                            put("originText", "답안의 근거 구간을 확인했습니다.")
                            put("learningText", "回答の根拠を確認しました。")
                        }) }
                    })
                    put("necessaryCorrections", buildJsonArray {})
                    put("optionalAlternatives", buildJsonArray {})
                    put("uncertainties", buildJsonArray {})
                }, 100, 50, "openai", "gpt-5-mini-2025-08-07")
            }
            val feedback = CuratedWritingFeedbackWorker(runner, snapshots, manifest.releaseId, model, clock)
            val plans = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId, clock = clock, feedback = feedback)
            runBlocking {
                snapshots.importManifest()
                for (index in 1..18) ExposedWritingSetUnitOfWork(runner, clock).write(9200L + index) {
                    val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                    growth.saveProfile(GrowthProfile(userId = 9200L + index, state = "ACTIVE",
                        baseLevelScore = 60.0, createdAt = now, updatedAt = now))
                }
            }
            testApplication {
                environment { config = MapApplicationConfig() }
                application {
                    configureSerialization(); configureStatusPages(); configureInternalAuthentication(auth)
                    routing { curatedWritingPlanRoutes(plans, settings, true) }
                }
                suspend fun post(user: Long, path: String, body: String) = client.post(root + path) {
                    bearerAuth(token(user)); contentType(ContentType.Application.Json); setBody(body)
                }
                suspend fun get(user: Long, path: String) = client.get(root + path) { bearerAuth(token(user)) }
                val saved = mutableListOf<Triple<Long, String, JsonObject>>()
                val failed = mutableListOf<Triple<Long, String, JsonObject>>()
                for (index in 1..18) {
                    val user = 9200L + index
                    val type = listOf("TRANSLATION", "GUIDED", "FREE")[(index - 1) % 3]
                    val started = Json.parseToJsonElement(post(user, "",
                        """{"writingType":"$type","targetItemCount":1}""").bodyAsText()).jsonObject
                    val setId = started.getValue("dailySetId").jsonPrimitive.content
                    val item = started.getValue("items").jsonArray.single().jsonObject
                    val itemId = item.getValue("itemId").jsonPrimitive.content
                    val revision = item.getValue("contentRevision").jsonPrimitive.content
                    val answer = "学習用回答${index}です。"
                    val submitted = post(user, "/items/$itemId/answers",
                        """{"answer":"$answer","contentRevision":"$revision"}""")
                    assertEquals(HttpStatusCode.OK, submitted.status, submitted.bodyAsText())
                    val plan = Json.parseToJsonElement(submitted.bodyAsText()).jsonObject
                    val attempt = plan.getValue("items").jsonArray.single().jsonObject
                        .getValue("attempts").jsonArray.single().jsonObject
                    assertEquals(answer, attempt.getValue("answer").jsonPrimitive.content)
                    assertEquals(JsonNull, attempt.getValue("evaluation"))
                    val entry = Triple(user, setId, plan)
                    if (answer in failures) {
                        assertEquals("FAILED", attempt.getValue("feedback").jsonObject.getValue("status").jsonPrimitive.content)
                        failed += entry
                    } else {
                        assertEquals("SUCCEEDED", attempt.getValue("feedback").jsonObject.getValue("status").jsonPrimitive.content)
                        saved += entry
                    }
                }
                assertEquals(18, calls)
                assertEquals(16, saved.size)
                assertEquals(2, failed.size)
                for ((user, setId, expected) in saved) assertEquals(expected,
                    Json.parseToJsonElement(get(user, "/$setId").bodyAsText()).jsonObject)
                assertEquals(18, calls)
                for ((user, setId, before) in failed) {
                    val old = before.getValue("items").jsonArray.single().jsonObject
                        .getValue("attempts").jsonArray.single().jsonObject
                    val answerId = old.getValue("answerId").jsonPrimitive.content
                    val response = post(user, "/answers/$answerId/feedback/retry", "{}")
                    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                    val after = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    val current = after.getValue("items").jsonArray.single().jsonObject
                        .getValue("attempts").jsonArray.single().jsonObject
                    assertEquals(old.getValue("answer"), current.getValue("answer"))
                    assertEquals(old.getValue("answerId"), current.getValue("answerId"))
                    assertEquals("SUCCEEDED", current.getValue("feedback").jsonObject.getValue("status").jsonPrimitive.content)
                    assertEquals(1, after.getValue("feedbackCompletedItemCount").jsonPrimitive.int)
                    assertEquals(after, Json.parseToJsonElement(get(user, "/$setId").bodyAsText()).jsonObject)
                }
                assertEquals(20, calls)
                for ((user, setId, expected) in saved) assertEquals(expected,
                    Json.parseToJsonElement(get(user, "/$setId").bodyAsText()).jsonObject)
                assertEquals(20, calls)
            }
        }
    }
}
