package jp.co.translacat.languagelearning.features.webaiplan.api

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.*
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import jp.co.translacat.languagelearning.bootstrap.configureInternalAuthentication
import jp.co.translacat.languagelearning.bootstrap.configureSerialization
import jp.co.translacat.languagelearning.bootstrap.configureStatusPages
import jp.co.translacat.languagelearning.features.webaiplan.application.*
import jp.co.translacat.languagelearning.features.webaiplan.domain.model.*
import jp.co.translacat.languagelearning.features.webaiplan.support.WebAiMemoryStore
import jp.co.translacat.languagelearning.shared.ai.*
import jp.co.translacat.languagelearning.shared.security.InternalApiSettings
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Date
import kotlin.test.*

/** 실제 Ktor JWT·본문 수신·LL 서비스·단일 합성 모델 실행을 연결한다. */
class WebAiRoutesTest {
    private val key = ByteArray(32) { (it + 31).toByte() }
    private val auth = InternalApiSettings(enabled = true, secretBase64 = Base64.getEncoder().encodeToString(key))
    private val base = "/internal/v1/language-learning/web-ai"
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    @Test
    fun `설정 없는 사용자의 직접 계획 생성은 HTTP 409로 안내한다`() = app { fixture ->
        // 준비: 화면 기본 언어가 있어도 저장된 언어가 없는 상태다.
        fixture.store.originLanguage = ""

        // 실행
        val response = client.post("$base/plans") {
            bearerAuth(userToken())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(change()))
        }

        // 검증
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("WEB_AI_SETTINGS_REQUIRED", response.objectBody().text("code"))
        assertTrue(fixture.store.writes.isEmpty())
        assertEquals(0, fixture.modelCalls)
    }

    @Test
    fun `키워드 선행 조건은 직접 API에도 적용하고 기존 계획 관리는 보존한다`() = app { fixture ->
        // 준비: 기존 계획은 두되 오늘 유효한 공통 키워드가 없는 상태다.
        val plan = fixture.service.save(123, null, change())
        val capability = fixture.service.connection(123, plan.id)
        fixture.defaultKeywords = emptyList()

        // 실행: UI를 거치지 않는 생성·키 발급·내부/외부 출제도 거부한다.
        val create = client.post("$base/plans") {
            bearerAuth(userToken()); contentType(ContentType.Application.Json)
            setBody(json.encodeToString(change()))
        }
        val connection = client.post("$base/plans/${plan.id}/connection") { bearerAuth(userToken()) }
        val manual = client.post("$base/plans/${plan.id}/runs") {
            bearerAuth(userToken()); contentType(ContentType.Application.Json)
            setBody("""{"executionKey":"manual"}""")
        }
        val external = client.post("$base/external/prompt") {
            bearerAuth(serviceToken()); header("X-Web-Ai-Token", capability)
            contentType(ContentType.Application.Json)
            setBody("""{"executionKey":"scheduled"}""")
        }
        val list = client.get("$base/plans") { bearerAuth(userToken()) }
        val revoke = client.delete("$base/plans/${plan.id}/connection") { bearerAuth(userToken()) }
        val delete = client.delete("$base/plans/${plan.id}") { bearerAuth(userToken()) }

        // 검증: 원인을 알려 주고 기존 계획의 정리·연동 해제는 허용한다.
        listOf(create, connection, manual, external).forEach {
            assertEquals(HttpStatusCode.Conflict, it.status)
            assertEquals("WEB_AI_KEYWORDS_REQUIRED", it.objectBody().text("code"))
        }
        assertEquals(HttpStatusCode.OK, list.status)
        assertEquals(HttpStatusCode.NoContent, revoke.status)
        assertEquals(HttpStatusCode.NoContent, delete.status)
        assertEquals(0, fixture.modelCalls)
    }

    @Test
    fun `공통 키워드가 있어도 빈 계획별 선택은 별도 오류로 안내한다`() = app { fixture ->
        // 준비
        val input = change().copy(keywordMode = "CUSTOM", keywords = emptyList())

        // 실행
        val response = client.post("$base/plans") {
            bearerAuth(userToken()); contentType(ContentType.Application.Json)
            setBody(json.encodeToString(input))
        }

        // 검증
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("WEB_AI_PLAN_KEYWORDS_REQUIRED", response.objectBody().text("code"))
        assertTrue(fixture.store.writes.isEmpty())
    }

    @Test
    fun `사용자 JWT와 서비스 JWT는 서로 다른 경로에만 사용할 수 있다`() = app { fixture ->
        // 준비와 실행: 인증이 없거나 용도가 반대인 토큰은 업무 호출 전에 거부된다.
        val anonymous = client.get("$base/plans")
        val serviceOnUser = client.get("$base/plans") { bearerAuth(serviceToken()) }
        val userOnExternal = client.post("$base/external/prompt") { bearerAuth(userToken()) }
        val missingCapability = client.post("$base/external/prompt") { bearerAuth(serviceToken()) }

        // 검증
        listOf(anonymous, serviceOnUser, userOnExternal, missingCapability).forEach {
            assertEquals(HttpStatusCode.Unauthorized, it.status)
        }
        assertTrue(fixture.store.writes.isEmpty())
        assertEquals(0, fixture.modelCalls)
    }

    @Test
    fun `서비스 토큰 scope 역할 유효기간과 용도를 엄격하게 확인한다`() = app { fixture ->
        // 준비
        val tokens = listOf(
            serviceToken(scopes = listOf("settings:read")),
            serviceToken(scopes = listOf("web-ai:external", "settings:read")),
            serviceToken(roles = listOf("ADMIN")),
            serviceToken(use = "ll-internal"),
            serviceToken(subject = "123"),
            serviceToken(ttl = 121),
            serviceToken(issued = -180, ttl = 60),
        )

        // 실행과 검증
        for (token in tokens) {
            val response = client.post("$base/external/prompt") {
                bearerAuth(token)
                header("X-Web-Ai-Token", "tcwa_" + "a".repeat(43))
                contentType(ContentType.Application.Json)
                setBody("""{"executionKey":"synthetic"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
        assertTrue(fixture.store.writes.isEmpty())
    }

    @Test
    fun `사용자 ID 헤더와 query는 계획의 소유자를 바꾸지 못한다`() = app { fixture ->
        // 준비
        val input = change()

        // 실행
        val response = client.post("$base/plans?userId=999") {
            bearerAuth(userToken(123))
            header("X-User-Id", "999")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(input))
        }

        // 검증
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, fixture.service.plans(123).size)
        assertTrue(fixture.service.plans(999).isEmpty())
        val id = response.objectBody().text("id")
        val otherOwner = client.post("$base/plans/$id/connection") { bearerAuth(userToken(999)) }
        assertEquals(HttpStatusCode.NotFound, otherOwner.status)
    }

    @Test
    fun `알 수 없는 필드와 계획별 언어 재정의는 JSON 경계에서 거부한다`() = app { fixture ->
        // 준비
        val valid = json.encodeToJsonElement(change()).jsonObject
        val fields = mapOf(
            "userId" to JsonPrimitive(999), "originLanguage" to JsonPrimitive("en"),
            "learningLanguage" to JsonPrimitive("fr"), "feedbackLanguage" to JsonPrimitive("en"),
        )

        // 실행과 검증
        for ((field, value) in fields) {
            val response = client.post("$base/plans") {
                bearerAuth(userToken())
                contentType(ContentType.Application.Json)
                setBody(JsonObject(valid + (field to value)).toString())
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, field)
            assertEquals("WEB_AI_INVALID", response.objectBody().text("code"))
        }
        val malformed = client.post("$base/plans") {
            bearerAuth(userToken()); contentType(ContentType.Application.Json); setBody("{")
        }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertTrue(fixture.store.writes.isEmpty())
    }

    @Test
    fun `계획 revision 충돌과 잘못된 식별자는 상태를 변경하지 않는다`() = app { fixture ->
        // 준비
        val plan = fixture.service.save(123, null, change())
        val update = change().copy(revision = plan.revision, name = "Changed plan")

        // 실행
        val saved = client.put("$base/plans/${plan.id}") {
            bearerAuth(userToken()); contentType(ContentType.Application.Json); setBody(json.encodeToString(update))
        }
        val conflict = client.put("$base/plans/${plan.id}") {
            bearerAuth(userToken()); contentType(ContentType.Application.Json); setBody(json.encodeToString(update))
        }
        val invalidId = client.get("$base/runs/not-a-uuid") { bearerAuth(userToken()) }

        // 검증
        assertEquals(HttpStatusCode.OK, saved.status)
        assertEquals(2, saved.objectBody().getValue("revision").jsonPrimitive.int)
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertEquals(HttpStatusCode.BadRequest, invalidId.status)
        assertEquals(2L, fixture.service.plans(123).single().revision)
        assertEquals("Changed plan", fixture.service.plans(123).single().name)
    }

    @Test
    fun `capability 실행 제출 중복 전송과 폐기를 실제 라우트에서 처리한다`() = app { fixture ->
        // 준비: 연결 키는 서버 응답으로 한 번 발급하며 결과 본문에 넣지 않는다.
        val plan = fixture.service.save(123, null, change())
        val connection = client.post("$base/plans/${plan.id}/connection") { bearerAuth(userToken()) }
        val capability = connection.objectBody().text("token")
        assertEquals("no-store", connection.headers[HttpHeaders.CacheControl])

        // 실행: 외부 토큰은 같은 실행 key를 재사용하고 내부 Mini가 공식 점수를 판정한다.
        val prompt = client.post("$base/external/prompt?userId=999") {
            bearerAuth(serviceToken()); header("X-Web-Ai-Token", capability); header("X-User-Id", "999")
            contentType(ContentType.Application.Json); setBody("""{"executionKey":"2026-10-15"}""")
        }
        assertEquals(HttpStatusCode.OK, prompt.status)
        val runId = prompt.objectBody().text("id")
        val repeatedPrompt = client.post("$base/external/prompt") {
            bearerAuth(serviceToken()); header("X-Web-Ai-Token", capability)
            contentType(ContentType.Application.Json); setBody("""{"executionKey":"arbitrary-random-key"}""")
        }
        assertEquals(HttpStatusCode.OK, repeatedPrompt.status)
        assertEquals(runId, repeatedPrompt.objectBody().text("id"))
        val payload = WebAiResultSubmission(
            runId, "batch-1", "# Original feedback\n\n| before | after |\n| --- | --- |", listOf(
                WebAiSubmittedItem("WRITING_TRANSLATION:STANDARD:1", "WRITING_TRANSLATION", "STANDARD",
                    "Synthetic task", "Synthetic answer", sourceText = "합성 원문", externalScore = 100.0),
            ),
        )
        suspend fun submit() = client.post("$base/external/results") {
            bearerAuth(serviceToken()); header("X-Web-Ai-Token", capability)
            contentType(ContentType.Application.Json); setBody(json.encodeToString(payload))
        }
        val first = submit()
        val repeated = submit()
        val resultId = first.objectBody().text("id")
        assertEquals(0, fixture.modelCalls)

        // 비동기 worker를 결정적으로 진행한 뒤 실제 조회 라우트에서 확정 결과를 읽는다.
        fixture.service.processQueued(123, resultId)
        val completed = client.get("$base/results/$resultId") { bearerAuth(userToken()) }
        val repeatedAfterCompletion = submit()
        val revoke = client.delete("$base/plans/${plan.id}/connection") { bearerAuth(userToken()) }
        val revoked = client.post("$base/external/prompt") {
            bearerAuth(serviceToken()); header("X-Web-Ai-Token", capability)
            contentType(ContentType.Application.Json); setBody("""{"executionKey":"after-revoke"}""")
        }
        val wrongOwner = client.get("$base/results/$resultId") { bearerAuth(userToken(999)) }

        // 검증: 답안·원문은 그대로 남고 중복 제출은 모델과 성장 점수를 두 번 추가하지 않는다.
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, repeated.status)
        assertEquals(resultId, repeated.objectBody().text("id"))
        assertEquals(resultId, repeatedAfterCompletion.objectBody().text("id"))
        assertEquals("VERIFIED", completed.objectBody().text("status"))
        assertEquals(payload.originalFeedbackMarkdown, first.objectBody().text("originalFeedbackMarkdown"))
        assertEquals(69.0, completed.objectBody().getValue("items").jsonArray.single().jsonObject
            .getValue("officialScore").jsonPrimitive.double)
        assertEquals(1, fixture.modelCalls)
        assertEquals(1, fixture.store.growthApplications.size)
        assertEquals(123L, fixture.store.growthApplications.single().userId)
        assertEquals(HttpStatusCode.NoContent, revoke.status)
        assertEquals(HttpStatusCode.Unauthorized, revoked.status)
        assertEquals(HttpStatusCode.NotFound, wrongOwner.status)
    }

    @Test
    fun `한 계획 capability는 같은 사용자의 다른 계획 결과를 제출할 수 없다`() = app { fixture ->
        // 준비
        val first = fixture.service.save(123, null, change("First plan"))
        val second = fixture.service.save(123, null, change("Second plan"))
        val token = fixture.service.connection(123, first.id)
        val otherRun = fixture.service.run(123, second.id, "other-plan")
        val payload = WebAiResultSubmission(otherRun.id, "batch", "Synthetic feedback", listOf(
            WebAiSubmittedItem("WRITING_TRANSLATION:STANDARD:1", "WRITING_TRANSLATION", "STANDARD",
                "Synthetic task", "Synthetic answer"),
        ))

        // 실행
        val response = client.post("$base/external/results") {
            bearerAuth(serviceToken()); header("X-Web-Ai-Token", token)
            contentType(ContentType.Application.Json); setBody(json.encodeToString(payload))
        }

        // 검증
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0, fixture.modelCalls)
        assertTrue(fixture.store.growthApplications.isEmpty())
    }

    @Test
    fun `Content Length 없는 실제 2MiB 초과 본문도 서비스를 호출하기 전에 차단한다`() = app { fixture ->
        // 준비: Content-Length를 제공하지 않는 실제 전송 콘텐츠를 만든다.
        val payload = ByteArray(2 * 1024 * 1024 + 1) { 32 }
        val streamed = object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.Application.Json
            override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(payload) }
        }

        // 실행
        val response = client.post("$base/plans") { bearerAuth(userToken()); setBody(streamed) }

        // 검증
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("WEB_AI_PAYLOAD_TOO_LARGE", response.objectBody().text("code"))
        assertTrue(fixture.store.writes.isEmpty())
        assertEquals(0, fixture.modelCalls)
    }

    private fun app(block: suspend ApplicationTestBuilder.(Fixture) -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        val fixture = Fixture()
        application {
            configureSerialization()
            configureStatusPages()
            configureInternalAuthentication(auth)
            routing { webAiRoutes(fixture.service) }
        }
        block(fixture)
    }

    private inner class Fixture {
        val store = WebAiMemoryStore()
        var defaultKeywords = listOf(WebAiKeyword("SYSTEM:1", "요건정의", "requirements"))
        var modelCalls = 0
        private val clock = Clock.fixed(Instant.parse("2026-10-15T09:00:00Z"), ZoneOffset.UTC)
        private val verifier = WebAiVerificationExecution(ModelExecutionPort {
            modelCalls++
            ModelExecutionResult(writingOutput, 1, 1, "synthetic", "synthetic")
        }, clock)
        val service = WebAiPlanService(store, WebAiContextProvider {
            WebAiContext(store.originLanguage, store.learningLanguage, "ko", defaultKeywords, defaultKeywords)
        }, verifier::verify, clock, asynchronous = true)
    }

    private fun change(name: String = "Synthetic plan") = WebAiPlanChange(
        name = name, timezone = "Asia/Tokyo", time = "18:00", recurrence = "DAILY",
        areas = listOf(WebAiArea("WRITING_TRANSLATION", 2, 2, 2)),
    )

    private fun userToken(user: Long = 123): String {
        val now = Instant.now()
        return JWT.create().withIssuer(auth.issuer).withAudience(auth.audience).withSubject(user.toString())
            .withClaim("service", auth.callerService).withClaim("tokenUse", "ll-internal")
            .withArrayClaim("roles", arrayOf("USER")).withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(60))).sign(Algorithm.HMAC256(key))
    }

    private fun serviceToken(
        scopes: List<String> = listOf("web-ai:external"), roles: List<String> = emptyList(),
        use: String = "ll-web-ai-service", subject: String = auth.callerService, ttl: Long = 60, issued: Long = 0,
    ): String {
        val now = Instant.now().plusSeconds(issued)
        return JWT.create().withIssuer(auth.issuer).withAudience(auth.audience).withSubject(subject)
            .withClaim("service", auth.callerService).withClaim("tokenUse", use)
            .withArrayClaim("scopes", scopes.toTypedArray()).withArrayClaim("roles", roles.toTypedArray())
            .withIssuedAt(Date.from(now)).withExpiresAt(Date.from(now.plusSeconds(ttl))).sign(Algorithm.HMAC256(key))
    }

    private suspend fun HttpResponse.objectBody() = Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.text(field: String) = getValue(field).jsonPrimitive.content

    private val writingOutput = buildJsonObject {
        put("taskReview", buildJsonObject {
            put("evaluable", true)
            put("difficultyAppropriate", true)
            put("reason", "합성 과제 검토")
        })
        put("evaluation", Json.parseToJsonElement(
        """{
          "scores":{"meaning":40,"grammar":90,"vocabulary":90,"naturalness":90,"expression":90},
          "strengths":[{"originText":"합성 강점","learningText":"合成の長所"}],
          "weaknesses":[{"originText":"합성 개선점","learningText":"合成の改善点"}],
          "corrections":[],"recommendedAnswers":["合成回答一","合成回答二"],
          "explanation":{"originText":"합성 해설","learningText":"合成の説明"},
          "profileSignals":{"strengthTags":[],"weaknessTags":[],"grammarPatterns":[],"vocabularyPatterns":[],
            "naturalnessPatterns":[],"expressionPatterns":[],"meaningPatterns":[],"recommendedFocus":[]}
        }""",
        ))
    }
}
