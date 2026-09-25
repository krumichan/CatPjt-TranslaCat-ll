package jp.co.translacat.languagelearning.features.growth.api

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import jp.co.translacat.languagelearning.bootstrap.*
import jp.co.translacat.languagelearning.shared.security.InternalApiSettings
import jp.co.translacat.languagelearning.support.MemoryGrowthUnitOfWork
import jp.co.translacat.languagelearning.support.GrowthFixtures as F
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.*
import kotlin.test.*

class GrowthRoutesTest {
    private val key=ByteArray(32){it.toByte()}
    private val settings=InternalApiSettings(enabled=true,secretBase64=Base64.getEncoder().encodeToString(key))
    private val write="/internal/v1/service/language-learning/growth/commands"
    private val read="/internal/v1/language-learning/growth/snapshot"
    private fun token(service:Boolean=true,user:Long=123,use:String=if(service)"ll-growth-v1" else "ll-internal",scope:String="growth:write"):String {
        val now=Instant.now();val b=JWT.create().withIssuer(settings.issuer).withAudience(settings.audience).withSubject(if(service) settings.callerService else user.toString())
            .withClaim("service",settings.callerService).withClaim("tokenUse",use).withIssuedAt(Date.from(now)).withExpiresAt(Date.from(now.plusSeconds(60)))
        if(service)b.withArrayClaim("scopes",arrayOf(scope)) else b.withArrayClaim("roles",arrayOf("USER"))
        return b.sign(Algorithm.HMAC256(key))
    }
    private fun query(minimum:Long=0,operations:JsonArray=JsonArray(emptyList()))=buildJsonObject {
        put("sourceInstanceId",F.SOURCE);put("minimumSequence",minimum);put("previewOperations",operations);put("masteryKeys",JsonNull)
    }.toString()
    @Test fun `성장 write scope와 사용자 read 인증을 분리한다`() { testApplication {
        environment {config=MapApplicationConfig()};val db=MemoryGrowthUnitOfWork()
        application {configureSerialization();configureStatusPages();configureInternalAuthentication(settings);routing{growthRoutes(db,F.SOURCE)}}
        assertEquals(HttpStatusCode.Unauthorized,client.post(write).status)
        for(t in listOf(token(false),token(scope="settings:read"),token(use="ll-learning-results-v1")))
            assertEquals(HttpStatusCode.Unauthorized,client.post(write){bearerAuth(t);contentType(ContentType.Application.Json);setBody(F.selectedEnvelope().toString())}.status)
        assertEquals(HttpStatusCode.Unauthorized,client.post(read){bearerAuth(token());contentType(ContentType.Application.Json);setBody(query())}.status)
        assertTrue(db.state.receipts.isEmpty())
    } }
    @Test fun `정상 commit 재전달 및 sequence gap을 구분한다`() { testApplication {
        environment{config=MapApplicationConfig()};val db=MemoryGrowthUnitOfWork()
        application{configureSerialization();configureStatusPages();configureInternalAuthentication(settings);routing{growthRoutes(db,F.SOURCE)}}
        suspend fun send(body:String)=client.post(write){bearerAuth(token());contentType(ContentType.Application.Json);setBody(body)}
        assertTrue(send(F.selectedEnvelope().toString()).bodyAsText().contains("APPLIED"))
        assertTrue(send(F.selectedEnvelope().toString()).bodyAsText().contains("DUPLICATE"))
        assertEquals(HttpStatusCode.Conflict,send(F.selectedEnvelope(3,"8d890a34-a91b-4a20-a9aa-f541bcb82105").toString()).status)
        assertEquals(1,db.state.mastery(123,"여행")!!.selectedCount)
    } }
    @Test fun `watermark 미반영은 빈 정상 응답 대신 503을 반환한다`() { testApplication {
        environment{config=MapApplicationConfig()};val db=MemoryGrowthUnitOfWork()
        application{configureSerialization();configureStatusPages();configureInternalAuthentication(settings);routing{growthRoutes(db,F.SOURCE)}}
        val response=client.post(read){bearerAuth(token(false));contentType(ContentType.Application.Json);setBody(query(1))}
        assertEquals(HttpStatusCode.ServiceUnavailable,response.status);assertEquals("1",response.headers[HttpHeaders.RetryAfter]);assertTrue(response.bodyAsText().contains("GROWTH_SYNC_PENDING"))
    } }
    @Test fun `preview와 다른 사용자 조회는 DB와 사용자 경계를 넘지 않는다`() { testApplication {
        environment{config=MapApplicationConfig()};val db=MemoryGrowthUnitOfWork()
        application{configureSerialization();configureStatusPages();configureInternalAuthentication(settings);routing{growthRoutes(db,F.SOURCE)}}
        val ops=Json.parseToJsonElement(F.selectedEnvelope().getValue("payloadJson").jsonPrimitive.content).jsonObject.getValue("operations").jsonArray
        val response=client.post(read){bearerAuth(token(false));contentType(ContentType.Application.Json);setBody(query(operations=ops))}
        assertEquals(HttpStatusCode.OK,response.status);assertTrue(response.bodyAsText().contains("여행"));assertTrue(db.state.masteries.isEmpty());assertTrue(db.state.receipts.isEmpty())
        client.post(write){bearerAuth(token());contentType(ContentType.Application.Json);setBody(F.selectedEnvelope().toString())}
        val other=client.post(read){bearerAuth(token(false,456));contentType(ContentType.Application.Json);setBody(query())}
        assertEquals(0,Json.parseToJsonElement(other.bodyAsText()).jsonObject.getValue("masteries").jsonArray.size)
    } }
    @Test fun `잘못된 본문 비활성 사용자와 크기 초과는 안전하게 거부한다`() { testApplication {
        environment{config=MapApplicationConfig()};val db=MemoryGrowthUnitOfWork()
        application{configureSerialization();configureStatusPages();configureInternalAuthentication(settings);routing{growthRoutes(db,F.SOURCE)}}
        suspend fun send(body:String)=client.post(write){bearerAuth(token());contentType(ContentType.Application.Json);setBody(body)}
        assertEquals(HttpStatusCode.BadRequest,send("{}").status)
        assertEquals(HttpStatusCode.PayloadTooLarge,send(" ".repeat(GrowthWire.MAX_BODY_BYTES+1)).status)
        db.state.inactive.add(123);assertEquals(HttpStatusCode.Forbidden,send(F.selectedEnvelope().toString()).status);assertTrue(db.state.receipts.isEmpty())
    } }
}
