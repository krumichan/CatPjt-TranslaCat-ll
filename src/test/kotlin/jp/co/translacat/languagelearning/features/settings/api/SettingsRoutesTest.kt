package jp.co.translacat.languagelearning.features.settings.api

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import jp.co.translacat.languagelearning.bootstrap.configureInternalAuthentication
import jp.co.translacat.languagelearning.bootstrap.configureSerialization
import jp.co.translacat.languagelearning.bootstrap.configureStatusPages
import jp.co.translacat.languagelearning.module
import jp.co.translacat.languagelearning.features.settings.application.SettingsOperations
import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsResult
import jp.co.translacat.languagelearning.features.settings.domain.model.*
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import jp.co.translacat.languagelearning.shared.security.InternalApiSettings
import jp.co.translacat.languagelearning.support.SettingsFixtures as F
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.Base64
import java.util.Date
import kotlin.test.*

/** DB 없이 실제 Ktor 인증·직렬화·오류 경계와 업무 포트의 호출 여부를 검증한다. */
class SettingsRoutesTest {
    private val key=ByteArray(32) { (it+1).toByte() }
    private val settings=InternalApiSettings(enabled=true,secretBase64=Base64.getEncoder().encodeToString(key))
    private val userPath="/internal/v1/language-learning/settings"
    private val adminPath="/internal/v1/admin/language-learning/settings"

    @Test fun `인증이 없으면 업무 서비스를 호출하지 않는다`() = app { ops ->
        assertEquals(HttpStatusCode.Unauthorized,client.get(userPath).status);assertTrue(ops.calls.isEmpty())
    }
    @Test fun `서명이 잘못되면 거부한다`() = app { ops ->
        val response=client.get(userPath){bearerAuth(token(key=ByteArray(32){99}))}
        assertEquals(HttpStatusCode.Unauthorized,response.status);assertTrue(ops.calls.isEmpty())
    }
    @Test fun `발급자 audience 서비스 용도가 다르면 거부한다`() = app { ops ->
        for (t in listOf(token(issuer="other"),token(audience="web"),token(service="other"),token(use="login"))) {
            assertEquals(HttpStatusCode.Unauthorized,client.get(userPath){bearerAuth(t)}.status)
        }
        assertTrue(ops.calls.isEmpty())
    }
    @Test fun `만료 토큰과 과도한 유효기간은 거부한다`() = app { ops ->
        for (t in listOf(token(issued=-180,ttl=60),token(ttl=121),token(issued=20,ttl=60))) {
            assertEquals(HttpStatusCode.Unauthorized,client.get(userPath){bearerAuth(t)}.status)
        }
        assertTrue(ops.calls.isEmpty())
    }
    @Test fun `iat exp sub 역할은 필수이며 타입도 검증한다`() = app { ops ->
        for(t in listOf(token(includeIssued=false),token(includeExpires=false),token(subject="0"),token(subject="abc"),token(roles=emptyList()),token(roles=listOf("ROOT")))) {
            assertEquals(HttpStatusCode.Unauthorized,client.get(userPath){bearerAuth(t)}.status)
        }
        assertTrue(ops.calls.isEmpty())
    }
    @Test fun `사용자 ID 헤더와 query는 JWT 주체를 바꾸지 못한다`() = app { ops ->
        val response=client.get("$userPath?userId=999") { bearerAuth(token(subject="123"));header("X-User-Id","999");header("X-User-Role","ADMIN") }
        assertEquals(HttpStatusCode.OK,response.status);assertEquals(listOf("get:123"),ops.calls)
    }
    @Test fun `조회 body는 기존 BE의 23개 필드와 null을 유지한다`() = app { _ ->
        val response=client.get(userPath){bearerAuth(token())}
        assertEquals(HttpStatusCode.OK,response.status)
        val body=Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(23,body.size);assertEquals(JsonNull,body["pendingEffectiveDate"])
        assertEquals(JsonNull,body["originLanguage"]);assertEquals(false,body.getValue("configured").jsonPrimitive.boolean)
        assertEquals(5,body.getValue("dailySentenceCount").jsonPrimitive.int)
        assertFalse("userId" in body);assertFalse("resultCode" in body)
    }
    @Test fun `정상 PATCH는 검증된 주체와 요청 값을 전달한다`() = app { ops ->
        val response=client.patch(userPath) { bearerAuth(token(subject="456"));contentType(ContentType.Application.Json)
            setBody("""{"originLanguage":"ko","learningLanguage":"ja","dailySentenceCount":7}""") }
        assertEquals(HttpStatusCode.OK,response.status);assertEquals(listOf("update:456"),ops.calls)
        assertEquals(7,ops.lastChange?.dailySentenceCount)
    }
    @Test fun `명시적 null과 누락은 미변경 요청으로 전달한다`() = app { ops ->
        val response=client.patch(userPath) {bearerAuth(token());contentType(ContentType.Application.Json);setBody("""{"dailySentenceCount":null}""")}
        assertEquals(HttpStatusCode.OK,response.status);assertEquals(UserSettingsChange(),ops.lastChange)
    }
    @Test fun `알 수 없는 필드와 잘못된 숫자 또는 JSON은 400이다`() = app { ops ->
        for(body in listOf("""{"userId":999}""","""{"dailySentenceCount":"bad"}""","""{"dailySentenceCount":999999999999}""","{", "[]")) {
            val response=client.patch(userPath){bearerAuth(token());contentType(ContentType.Application.Json);setBody(body)}
            assertEquals(HttpStatusCode.BadRequest,response.status,body)
        }
        assertTrue(ops.calls.isEmpty())
    }
    @Test fun `Task null 원소는 정책으로 전달하고 알 수 없는 enum은 400이다`() = app { ops ->
        val bad=client.patch(userPath){bearerAuth(token());contentType(ContentType.Application.Json);setBody("""{"defaultListeningTaskTypes":["UNKNOWN"]}""")}
        assertEquals(HttpStatusCode.BadRequest,bad.status);assertTrue(ops.calls.isEmpty())
        val input=client.patch(userPath){bearerAuth(token());contentType(ContentType.Application.Json);setBody("""{"defaultListeningTaskTypes":[null]}""")}
        assertEquals(HttpStatusCode.OK,input.status);assertEquals(listOf(null),ops.lastChange?.defaultListeningTaskTypes)
    }
    @Test fun `일반 사용자에게 관리자 API를 노출하지 않는다`() = app { ops ->
        assertEquals(HttpStatusCode.Forbidden,client.get(adminPath){bearerAuth(token())}.status)
        assertEquals(HttpStatusCode.Forbidden,client.patch(adminPath){bearerAuth(token());contentType(ContentType.Application.Json);setBody("{")}.status)
        assertTrue(ops.calls.isEmpty())
    }
    @Test fun `관리자 조회는 30개 기존 필드를 반환한다`() = app { ops ->
        val response=client.get(adminPath){bearerAuth(token(roles=listOf("ADMIN")))}
        assertEquals(HttpStatusCode.OK,response.status);assertEquals(listOf("admin-get"),ops.calls)
        assertEquals(30,Json.parseToJsonElement(response.bodyAsText()).jsonObject.size)
    }
    @Test fun `관리자 PATCH의 감사 주체는 토큰에서만 가져온다`() = app { ops ->
        val response=client.patch(adminPath){bearerAuth(token(subject="987",roles=listOf("ADMIN")));contentType(ContentType.Application.Json);setBody("""{"dailyKeywordMaxCount":8}""")}
        assertEquals(HttpStatusCode.OK,response.status);assertEquals(listOf("admin-update:987"),ops.calls)
    }
    @Test fun `업무 오류는 원본 코드와 안전한 메시지를 반환한다`() = app { ops ->
        ops.failure=LearningBusinessException("LANGUAGE_LEARNING_SETTING_INVALID","검증 오류")
        val response=client.get(userPath){bearerAuth(token())}
        assertEquals(HttpStatusCode.BadRequest,response.status)
        assertEquals("LANGUAGE_LEARNING_SETTING_INVALID",Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("code").jsonPrimitive.content)
    }
    @Test fun `SQL과 내부 예외 원문은 응답에 노출하지 않는다`() = app { ops ->
        ops.failure=IllegalStateException("jdbc:mysql://secret.internal password=DO_NOT_LEAK")
        val response=client.get(userPath){bearerAuth(token())}
        assertEquals(HttpStatusCode.InternalServerError,response.status)
        assertFalse(response.bodyAsText().contains("DO_NOT_LEAK"));assertFalse(response.bodyAsText().contains("jdbc:"))
    }
    @Test fun `외부 BE 경로를 인증 없는 별칭으로 만들지 않는다`() = app { ops ->
        assertEquals(HttpStatusCode.NotFound,client.get("/api/v1/language-learning/settings").status)
        assertTrue(ops.calls.isEmpty())
    }
    @Test fun `internalApi 비활성 상태에서 기존 health는 정상이고 Settings는 미등록이다`() = testApplication {
        environment { config=MapApplicationConfig("database.enabled" to "false","internalApi.enabled" to "false") }
        application { module() }
        assertEquals(HttpStatusCode.OK,client.get("/health").status)
        assertEquals(HttpStatusCode.NotFound,client.get(userPath){bearerAuth(token())}.status)
    }

    private fun token(subject:String="123",roles:List<String> = listOf("USER"),issuer:String="translacat-be",audience:String="translacat-ll",
        service:String="translacat-be",use:String="ll-internal",key:ByteArray=this.key,issued:Long=0,ttl:Long=60,
        includeIssued:Boolean=true,includeExpires:Boolean=true):String {
        val now=Instant.now().plusSeconds(issued)
        val builder=JWT.create().withIssuer(issuer).withAudience(audience).withSubject(subject)
            .withArrayClaim("roles",roles.toTypedArray()).withClaim("service",service).withClaim("tokenUse",use)
        if(includeIssued)builder.withIssuedAt(Date.from(now))
        if(includeExpires)builder.withExpiresAt(Date.from(now.plusSeconds(ttl)))
        return builder.sign(Algorithm.HMAC256(key))
    }
    private fun app(block:suspend ApplicationTestBuilder.(FakeOperations)->Unit)=testApplication {
        environment { config=MapApplicationConfig() }
        val ops=FakeOperations()
        application { configureSerialization();configureStatusPages();configureInternalAuthentication(settings);routing {settingsRoutes(ops)} }
        block(ops)
    }
    private class FakeOperations : SettingsOperations {
        val calls=mutableListOf<String>();var lastChange:UserSettingsChange?=null;var failure:Throwable?=null
        override suspend fun getUser(userId:Long):UserSettingsResult {
            calls += "get:$userId";failure?.let {throw it};return UserSettingsResult(F.user(userId),F.policy())
        }
        override suspend fun updateUser(userId:Long,change:UserSettingsChange):UserSettingsResult {
            calls += "update:$userId";lastChange=change;return UserSettingsResult(F.user(userId),F.policy())
        }
        override suspend fun getAdmin():AdminSettings {calls += "admin-get";return F.admin()}
        override suspend fun updateAdmin(adminUserId:Long,change:AdminSettingsChange):AdminSettings {calls += "admin-update:$adminUserId";return F.admin()}
    }
}
