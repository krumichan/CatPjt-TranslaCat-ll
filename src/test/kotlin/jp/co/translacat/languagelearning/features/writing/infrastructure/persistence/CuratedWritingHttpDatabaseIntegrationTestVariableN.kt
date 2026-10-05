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
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.shared.security.InternalApiSettings
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import jp.co.translacat.languagelearning.support.SettingsFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.*
import java.util.Base64
import java.util.Date
import kotlin.test.*

/** 실제 MySQL + 인증된 Ktor HTTP. 외부 모델 client는 구성하지 않는다. */
class CuratedWritingHttpDatabaseIntegrationTestVariableN {
    private val date = LocalDate.parse("2026-10-04")
    private val clock = Clock.fixed(Instant.parse("2026-10-04T03:00:00Z"), ZoneOffset.UTC)
    private val key = ByteArray(32) { it.toByte() }
    private val auth = InternalApiSettings(enabled = true, secretBase64 = Base64.getEncoder().encodeToString(key))
    private val root = "/internal/v1/language-learning/writing/curated/plans"
    private fun token(user: Long): String {
        val issuedAt = Instant.now()
        return JWT.create().withIssuer("translacat-be").withAudience("translacat-ll")
            .withSubject(user.toString()).withClaim("service", "translacat-be").withClaim("tokenUse", "ll-internal")
            .withArrayClaim("roles", arrayOf("USER")).withIssuedAt(Date.from(issuedAt))
            .withExpiresAt(Date.from(issuedAt.plusSeconds(120))).sign(Algorithm.HMAC256(key))
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
    fun `HTTP 목표 검증과 부분 동의 확대 제출 이력이 현재 계약을 지킨다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            // 준비: 충분한 합성 재고와 실제 DB owner를 준비한다.
            val runner = JdbcTransactionRunner(factory.database, 4)
            val manifest = CuratedWritingPlanFixtures.manifest(20)
            val snapshots = CuratedWritingStore(runner, manifest, clock)
            val plans = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId, clock = clock)
            runBlocking {
                snapshots.importManifest()
                seed(runner, 5101)
                seed(runner, 5102)
            }
            testApplication {
                environment { config = MapApplicationConfig() }
                application {
                    configureSerialization()
                    configureStatusPages()
                    configureInternalAuthentication(auth)
                    routing { curatedWritingPlanRoutes(plans, settings, true) }
                }
                suspend fun post(path: String, body: String, user: Long = 5101) = client.post(root + path) {
                    bearerAuth(token(user)); contentType(ContentType.Application.Json); setBody(body)
                }
                suspend fun get(path: String, user: Long = 5101) = client.get(root + path) { bearerAuth(token(user)) }

                // 실행·검증: 인증/타입/상한/소수/overflow/문자열/추가 필드는 쓰기 전에 거부한다.
                assertEquals(HttpStatusCode.Unauthorized, client.get("$root/config").status)
                val configResponse = get("/config")
                assertEquals(HttpStatusCode.OK, configResponse.status, configResponse.bodyAsText())
                val config = Json.parseToJsonElement(configResponse.bodyAsText()).jsonObject
                assertEquals(100, config.getValue("maxTargetItemCount").jsonPrimitive.int)
                for (raw in listOf("0", "-1", "1.5", "101", "2147483648", "\"5\"", "null")) {
                    assertEquals(HttpStatusCode.BadRequest,
                        post("", """{"writingType":"FREE","targetItemCount":$raw}""").status)
                }
                assertEquals(HttpStatusCode.BadRequest,
                    post("", """{"writingType":"FREE","targetItemCount":5,"allowPartial":"true"}""").status)
                assertEquals(HttpStatusCode.BadRequest,
                    post("", """{"writingType":"FREE","targetItemCount":5,"unexpected":true}""").status)
                val preview = post("/preview", """{"writingType":"FREE","targetItemCount":100}""")
                assertEquals(HttpStatusCode.OK, preview.status)
                assertFalse(preview.bodyAsText().contains("referenceAnswers"))
                assertEquals(HttpStatusCode.Conflict, post("", """{"writingType":"FREE","targetItemCount":100}""").status)

                // 실행:10개 시작→30개 확대→1개 제출. 참조는 제출한 항목에만 나타난다.
                val saved = Json.parseToJsonElement(post("", """{"writingType":"FREE","targetItemCount":10}""").bodyAsText()).jsonObject
                val id = saved.getValue("dailySetId").jsonPrimitive.content
                val expanded = post("/$id/target", """{"targetItemCount":30,"planRevision":1}""")
                assertEquals(HttpStatusCode.OK, expanded.status)
                val plan = Json.parseToJsonElement(expanded.bodyAsText()).jsonObject
                assertEquals(30, plan.getValue("acquiredItemCount").jsonPrimitive.int)
                assertEquals(2, plan.getValue("planRevision").jsonPrimitive.int)
                assertEquals(plan, Json.parseToJsonElement(post("/$id/target",
                    """{"targetItemCount":30,"planRevision":1}""").bodyAsText()))
                val item = plan.getValue("items").jsonArray.first().jsonObject
                val itemId = item.getValue("itemId").jsonPrimitive.content
                val revision = item.getValue("contentRevision").jsonPrimitive.content
                assertFalse(plan.getValue("items").jsonArray.any { "reference" in it.jsonObject })
                assertEquals(HttpStatusCode.NotFound, get("/$id", 5102).status)
                assertEquals(HttpStatusCode.Conflict, post("/$id/restore", """{"planRevision":1}""").status)
                assertEquals(HttpStatusCode.Conflict,
                    post("/items/$itemId/answers", """{"answer":"合成回答","contentRevision":"stale"}""").status)
                val answer = """{"answer":"合成回答","contentRevision":"$revision"}"""
                val submitted = post("/items/$itemId/answers", answer)
                assertEquals(HttpStatusCode.OK, submitted.status)
                val body = Json.parseToJsonElement(submitted.bodyAsText()).jsonObject
                assertEquals(1, body.getValue("submittedItemCount").jsonPrimitive.int)
                assertEquals(1, body.getValue("items").jsonArray.count { "reference" in it.jsonObject })
                assertEquals(body, Json.parseToJsonElement(post("/items/$itemId/answers", answer).bodyAsText()))
                assertEquals(body, Json.parseToJsonElement(get("/history/$date?writingType=FREE").bodyAsText()))
                assertEquals(HttpStatusCode.NotFound, post("/items/$itemId/answers", answer, 5102).status)

                // 검증: 부족한 사용자 계획은 명시적 동의로 PARTIAL로만 저장된다.
                val partial = post("", """{"writingType":"GUIDED","targetItemCount":100,"allowPartial":true}""", 5102)
                assertEquals(HttpStatusCode.OK, partial.status)
                val partialPlan = Json.parseToJsonElement(partial.bodyAsText()).jsonObject
                assertEquals("PARTIAL", partialPlan.getValue("status").jsonPrimitive.content)
                assertEquals(60, partialPlan.getValue("acquiredItemCount").jsonPrimitive.int)
                assertEquals(40, partialPlan.getValue("remainingItemCount").jsonPrimitive.int)
            }
        }
    }

    @Test
    fun `실제 HTTP에서 모든 목표와 세 유형을 시작하고 같은 snapshot을 조회한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            // 준비: 부족 재고 검증과 분리된 충분한 합성 재고, 27개 독립 owner.
            val runner = JdbcTransactionRunner(factory.database, 4)
            val manifest = CuratedWritingPlanFixtures.manifest(100)
            val snapshots = CuratedWritingStore(runner, manifest, clock)
            val plans = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId, clock = clock)
            val scenarios = listOf("TRANSLATION", "GUIDED", "FREE").flatMap { type ->
                listOf(1, 2, 3, 5, 10, 20, 30, 50, 100).map { target -> type to target }
            }
            runBlocking {
                snapshots.importManifest()
                scenarios.indices.forEach { seed(runner, 5200L + it) }
            }
            testApplication {
                environment { config = MapApplicationConfig() }
                application {
                    configureSerialization()
                    configureStatusPages()
                    configureInternalAuthentication(auth)
                    routing { curatedWritingPlanRoutes(plans, settings, true) }
                }
                for ((index, scenario) in scenarios.withIndex()) {
                    val (type, target) = scenario
                    val owner = 5200L + index
                    val payload = """{"writingType":"$type","targetItemCount":$target}"""

                    // 실행: 각 N과 유형을 인증된 HTTP로 시작하고 같은 요청을 재전송한다.
                    suspend fun start() = client.post(root) {
                        bearerAuth(token(owner))
                        contentType(ContentType.Application.Json)
                        setBody(payload)
                    }
                    val response = start()
                    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                    val saved = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    val id = saved.getValue("dailySetId").jsonPrimitive.content
                    val read = client.get("$root/$id") { bearerAuth(token(owner)) }
                    val repeated = start()

                    // 검증: 개수·정책·유형·원본 snapshot이 같고 제출 전 참고답안은 전송되지 않는다.
                    assertEquals(HttpStatusCode.OK, read.status)
                    assertEquals(HttpStatusCode.OK, repeated.status)
                    assertEquals(saved, Json.parseToJsonElement(read.bodyAsText()))
                    assertEquals(saved, Json.parseToJsonElement(repeated.bodyAsText()))
                    assertEquals(type, saved.getValue("writingType").jsonPrimitive.content)
                    assertEquals("curated-writing-variable-n-v1", saved.getValue("policyVersion").jsonPrimitive.content)
                    assertEquals(target, saved.getValue("targetItemCount").jsonPrimitive.int)
                    assertEquals(target, saved.getValue("acquiredItemCount").jsonPrimitive.int)
                    assertEquals(0, saved.getValue("submittedItemCount").jsonPrimitive.int)
                    assertEquals(0, saved.getValue("feedbackCompletedItemCount").jsonPrimitive.int)
                    val items = saved.getValue("items").jsonArray
                    assertEquals(target, items.size)
                    assertEquals(target, items.map { it.jsonObject.getValue("catalogId") }.distinct().size)
                    assertTrue(items.all { "reference" !in it.jsonObject })
                    assertFalse(response.bodyAsText().contains("referenceAnswers"))
                }
            }
        }
    }

    private suspend fun seed(runner: JdbcTransactionRunner, userId: Long) {
        ExposedWritingSetUnitOfWork(runner, clock).write(userId) {
            val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
            growth.saveProfile(GrowthProfile(userId = userId, state = "ACTIVE", baseLevelScore = 60.0,
                createdAt = now, updatedAt = now))
        }
    }
}
