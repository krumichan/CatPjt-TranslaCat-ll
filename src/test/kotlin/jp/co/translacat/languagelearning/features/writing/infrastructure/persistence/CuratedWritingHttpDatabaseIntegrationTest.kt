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
import jp.co.translacat.languagelearning.features.writing.api.curatedWritingRoutes
import jp.co.translacat.languagelearning.features.writing.api.WritingAnswerRouteContext
import jp.co.translacat.languagelearning.features.writing.api.writingPreparedRoutes
import jp.co.translacat.languagelearning.features.writing.application.*
import jp.co.translacat.languagelearning.features.writing.domain.model.NewWritingSet
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingManifestCodec
import jp.co.translacat.languagelearning.shared.ai.HttpModelExecution
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.shared.security.InternalApiSettings
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import jp.co.translacat.languagelearning.support.SettingsFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.Date
import kotlin.test.*

/** 실제 격리 MySQL, Ktor 인증/HTTP, 세 유형. Provider 호출은 구성하지 않는다. */
class CuratedWritingHttpDatabaseIntegrationTest {
    private val date = LocalDate.parse("2026-10-04")
    private val clock = Clock.fixed(Instant.parse("2026-10-04T03:00:00Z"), ZoneOffset.UTC)
    private val key = ByteArray(32) { it.toByte() }
    private val auth = InternalApiSettings(enabled = true, secretBase64 = Base64.getEncoder().encodeToString(key))
    private val root = "/internal/v1/language-learning/writing/curated"

    private fun token(user: Long) = JWT.create().withIssuer("translacat-be")
        .withAudience("translacat-ll").withSubject(user.toString())
        .withClaim("service", "translacat-be").withClaim("tokenUse", "ll-internal")
        .withArrayClaim("roles", arrayOf("USER"))
        .withIssuedAt(Date.from(Instant.now())).withExpiresAt(Date.from(Instant.now().plusSeconds(120)))
        .sign(Algorithm.HMAC256(key))

    private val settings = object : SettingsServiceOperations {
        override suspend fun userSnapshot(userId: Long) = UserSettingsSnapshot(userId, date,
            UserSettingsResult(SettingsFixtures.configured(userId), SettingsFixtures.policy()))
        override suspend fun learningDate(userId: Long) = date
        override suspend fun adminPolicy() = SettingsFixtures.admin()
        override suspend fun configuredLanguagePairs() = listOf(ConfiguredLanguagePair("ko", "ja"))
        override suspend fun listeningPolicy(): ListeningPolicy = error("not used")
    }

    @Test
    fun `큐레이션 세트가 있으면 기존 생성 HTTP도 정책 충돌을 반환한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            val runner = JdbcTransactionRunner(factory.database, 4)
            val source = checkNotNull(javaClass.classLoader.getResourceAsStream("writing/curated-candidates.json"))
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val store = CuratedWritingStore(runner, CuratedWritingManifestCodec.parse(source), clock)
            val work = ExposedWritingSetUnitOfWork(runner, clock)
            val state = WritingGenerationState(work)
            runBlocking {
                // 준비: QA 후보 세트를 먼저 만들고 기존 경로와 같은 owner·날짜·유형을 사용한다.
                store.importManifest()
                work.write(711) {
                    growth.saveProfile(GrowthProfile(userId = 711, state = "ACTIVE", baseLevelScore = 60.0,
                        createdAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                        updatedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)))
                }
                store.start(CuratedWritingStore.Start(711, date, WritingType.FREE,
                    "ko", "ja", false, true, 7))
            }
            // 충돌 전에 반환하므로 테스트용 모델 대상에는 HTTP 요청이 발생하지 않는다.
            HttpModelExecution("http://127.0.0.1:9", "synthetic-local-model-key").use { model ->
                testApplication {
                    environment { config = MapApplicationConfig() }
                    application {
                        configureSerialization()
                        configureStatusPages()
                        configureInternalAuthentication(auth)
                        routing {
                            writingPreparedRoutes(
                                state, WritingGenerationWorker(state, work, WritingGenerationExecution(model),
                                    WritingReviewExecution(model)), WritingReadService(work),
                                readContext = { date to 7 }, answers = WritingAnswerState(work),
                                evaluation = WritingEvaluationWorker(WritingEvaluationState(work),
                                    WritingEvaluationExecution(model)),
                                answerContext = { WritingAnswerRouteContext(date, 7, true, "ko", "ja") },
                                regenerationAllowed = { true },
                                createSet = { userId, type -> state.getOrCreate(NewWritingSet(userId, date, type,
                                    "legacy-conflict", 1, "{}")) },
                            )
                        }
                    }

                    // 실행: 기존 생성 endpoint에서 같은 날짜·유형을 다시 요청한다.
                    val response = client.post("/internal/v1/language-learning/writing/daily/sets") {
                        bearerAuth(token(711))
                        contentType(ContentType.Application.Json)
                        setBody("""{"writingType":"FREE"}""")
                    }

                    // 검증: 저장형 충돌을 500이나 비동기 생성으로 바꾸지 않는다.
                    assertEquals(HttpStatusCode.Conflict, response.status)
                    assertTrue(response.bodyAsText().contains("WRITING_POLICY_CONFLICT"))
                }
            }
        }
    }

    @Test
    fun `미승인 기본 경로는 차단되고 QA 세 유형은 인증 제출 조회 기록을 지킨다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            val runner = JdbcTransactionRunner(factory.database, 4)
            val source = checkNotNull(javaClass.classLoader.getResourceAsStream("writing/curated-candidates.json"))
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val store = CuratedWritingStore(runner, CuratedWritingManifestCodec.parse(source), clock)
            runBlocking {
                store.importManifest()
                for (owner in 701L..703L) {
                    ExposedWritingSetUnitOfWork(runner, clock).write(owner) {
                        growth.saveProfile(GrowthProfile(userId = owner, state = "ACTIVE", baseLevelScore = 60.0,
                            createdAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                            updatedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)))
                    }
                }
            }
            testApplication {
                environment { config = MapApplicationConfig() }
                application {
                    configureSerialization(); configureStatusPages(); configureInternalAuthentication(auth)
                    routing { curatedWritingRoutes(store, settings, qaOnly = false) }
                }
                val denied = client.post("$root/sets") {
                    bearerAuth(token(701)); contentType(ContentType.Application.Json)
                    setBody("""{"writingType":"TRANSLATION"}""")
                }
                assertEquals(HttpStatusCode.Conflict, denied.status)
                assertTrue(denied.bodyAsText().contains("WRITING_CONTENT_NOT_AVAILABLE"))
            }
            testApplication {
                environment { config = MapApplicationConfig() }
                application {
                    configureSerialization(); configureStatusPages(); configureInternalAuthentication(auth)
                    routing { curatedWritingRoutes(store, settings, qaOnly = true) }
                }
                assertEquals(HttpStatusCode.Unauthorized, client.post("$root/sets") {
                    contentType(ContentType.Application.Json); setBody("""{"writingType":"FREE"}""")
                }.status)
                assertEquals(HttpStatusCode.BadRequest, client.post("$root/sets") {
                    bearerAuth(token(701)); contentType(ContentType.Application.Json)
                    setBody("""{"writingType":"FREE","unexpected":true}""")
                }.status)
                assertEquals(HttpStatusCode.BadRequest, client.post("$root/sets") {
                    bearerAuth(token(701)); contentType(ContentType.Application.Json)
                    setBody("""{"writingType":"FREE","rePractice":{}}""")
                }.status)
                for ((index, type) in listOf("TRANSLATION", "GUIDED", "FREE").withIndex()) {
                    val owner = 701L + index
                    suspend fun open() = client.post("$root/sets") {
                        bearerAuth(token(owner)); contentType(ContentType.Application.Json)
                        setBody("""{"writingType":"$type"}""")
                    }
                    val created = open()
                    assertEquals(HttpStatusCode.OK, created.status, "$type ${created.bodyAsText()}")
                    val first = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                    assertEquals(5, first.getValue("items").jsonArray.size)
                    assertEquals("REFERENCE_ONLY", first.getValue("resultPolicy").jsonPrimitive.content)
                    assertEquals(created.bodyAsText(), open().bodyAsText())
                    val setId = first.getValue("dailySetId").jsonPrimitive.content
                    val item = first.getValue("items").jsonArray.first().jsonObject
                    assertFalse("reference" in item)
                    assertEquals(HttpStatusCode.NotFound, client.get("$root/sets/$setId") {
                        bearerAuth(token(if (owner == 701L) 702 else 701))
                    }.status)
                    val itemId = item.getValue("itemId").jsonPrimitive.content
                    val revision = item.getValue("contentRevision").jsonPrimitive.content
                    val stale = client.post("$root/items/$itemId/answers") {
                        bearerAuth(token(owner)); contentType(ContentType.Application.Json)
                        setBody("""{"answer":"日本語の回答","contentRevision":"${"0".repeat(64)}"}""")
                    }
                    assertEquals(HttpStatusCode.Conflict, stale.status)
                    val submitted = client.post("$root/items/$itemId/answers") {
                        bearerAuth(token(owner)); contentType(ContentType.Application.Json)
                        setBody("""{"answer":"日本語の回答","contentRevision":"$revision"}""")
                    }
                    assertEquals(HttpStatusCode.OK, submitted.status, "$type ${submitted.bodyAsText()}")
                    val after = Json.parseToJsonElement(submitted.bodyAsText()).jsonObject
                    assertTrue("reference" in after.getValue("items").jsonArray.first().jsonObject)
                    assertTrue(after.getValue("items").jsonArray.drop(1).none { "reference" in it.jsonObject })
                    assertEquals(submitted.bodyAsText(), client.get("$root/sets/$setId") {
                        bearerAuth(token(owner))
                    }.bodyAsText())
                    assertEquals(HttpStatusCode.OK, client.get("$root/history/$date?writingType=$type") {
                        bearerAuth(token(owner))
                    }.status)
                }
            }
        }
    }
}
