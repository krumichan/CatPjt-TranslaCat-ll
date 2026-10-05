package jp.co.translacat.languagelearning.features.overview

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
import jp.co.translacat.languagelearning.features.growth.application.GrowthTransaction
import jp.co.translacat.languagelearning.features.growth.application.GrowthUnitOfWork
import jp.co.translacat.languagelearning.features.overview.api.learningEvidenceRoutes
import jp.co.translacat.languagelearning.features.overview.application.LearningEvidenceQuery
import jp.co.translacat.languagelearning.shared.security.InternalApiSettings
import jp.co.translacat.languagelearning.support.MemoryGrowthUnitOfWork
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.*
import kotlin.test.*

class LearningEvidenceRoutesTest {
    private val key = ByteArray(32) { (it + 10).toByte() }
    private val auth = InternalApiSettings(enabled = true, secretBase64 = Base64.getEncoder().encodeToString(key))
    private val path = "/internal/v1/language-learning/overview/evidence?from=2026-10-01&to=2026-10-03"
    private fun token(user: Long = 123): String = JWT.create()
        .withIssuer(auth.issuer).withAudience(auth.audience).withSubject(user.toString())
        .withClaim("service", auth.callerService).withClaim("tokenUse", "ll-internal")
        .withArrayClaim("roles", arrayOf("USER")).withIssuedAt(Date.from(Instant.now()))
        .withExpiresAt(Date.from(Instant.now().plusSeconds(60))).sign(Algorithm.HMAC256(key))

    @Test
    fun `인증 owner만 허용하고 잘못된 필터와 중복 query를 400으로 거절한다`() = testApplication {
        // 준비
        environment { config = MapApplicationConfig() }
        val work = MemoryGrowthUnitOfWork()
        application {
            configureSerialization(); configureStatusPages(); configureInternalAuthentication(auth)
            routing { learningEvidenceRoutes(LearningEvidenceQuery(work)) }
        }

        // 실행 및 검증: 조회는 사용자/활동/평가를 생성하지 않는다.
        assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
        val empty = client.get(path) { bearerAuth(token()) }
        assertEquals(HttpStatusCode.OK, empty.status)
        assertTrue(Json.parseToJsonElement(empty.bodyAsText()).jsonObject.getValue("items").jsonArray.isEmpty())
        for (suffix in listOf("&userId=999", "&source=OTHER", "&limit=51", "&limit=abc", "&from=2026-10-02", "&cursor=bad")) {
            assertEquals(HttpStatusCode.BadRequest, client.get(path + suffix) { bearerAuth(token()) }.status)
        }
        work.state.inactive += 123
        assertEquals(HttpStatusCode.Forbidden, client.get(path) { bearerAuth(token()) }.status)
        assertTrue(work.state.profiles.isEmpty())
        assertTrue(work.state.activities.isEmpty())
    }

    @Test
    fun `저장소 장애는 빈 기록이나 0점으로 변환하지 않는다`() = testApplication {
        // 준비: HTTP/오류 매퍼는 실제, 저장소 장애만 합성한다.
        environment { config = MapApplicationConfig() }
        val unavailable = object : GrowthUnitOfWork {
            override suspend fun <T> read(block: GrowthTransaction.() -> T): T = error("synthetic unavailable")
            override suspend fun <T> write(userId: Long, block: GrowthTransaction.() -> T): T = error("must not write")
        }
        application {
            configureSerialization(); configureStatusPages(); configureInternalAuthentication(auth)
            routing { learningEvidenceRoutes(LearningEvidenceQuery(unavailable)) }
        }

        // 실행
        val response = client.get(path) { bearerAuth(token()) }

        // 검증
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("INTERNAL_ERROR", body.getValue("code").jsonPrimitive.content)
        assertFalse("items" in body)
    }
}
