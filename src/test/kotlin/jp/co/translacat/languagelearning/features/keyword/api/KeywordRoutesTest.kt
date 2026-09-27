package jp.co.translacat.languagelearning.features.keyword.api

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
import jp.co.translacat.languagelearning.features.keyword.application.DefaultKeywordOperations
import jp.co.translacat.languagelearning.features.keyword.application.KeywordLearningDate
import jp.co.translacat.languagelearning.features.keyword.application.KeywordLearningFacts
import jp.co.translacat.languagelearning.shared.security.InternalApiSettings
import jp.co.translacat.languagelearning.support.MemoryKeywordUnitOfWork
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeywordRoutesTest {
    private var serverStarted = false
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val settings = InternalApiSettings(enabled = true, secretBase64 = Base64.getEncoder().encodeToString(key))
    private val path = "/internal/v1/language-learning/keywords"
    private val adminPath = "/internal/v1/admin/language-learning/system-keywords"

    private fun token(
        user: Long = 123, admin: Boolean = false, started: Boolean? = null,
        use: String = "ll-keywords", issued: Long = 0, ttl: Long = 60, signingKey: ByteArray = key,
        wrongType: Boolean = false,
    ): String {
        val now = Instant.now().plusSeconds(issued)
        val builder =
            JWT.create().withIssuer("translacat-be").withAudience("translacat-ll").withSubject(user.toString())
                .withClaim("service", "translacat-be").withClaim("tokenUse", use)
                .withArrayClaim("roles", arrayOf(if (admin) "ADMIN" else "USER"))
                .withIssuedAt(Date.from(now)).withExpiresAt(Date.from(now.plusSeconds(ttl)))
        if (started != null) builder.withClaim("keywordLearningStarted", started)
        if (wrongType) builder.withClaim("keywordLearningStarted", "false")
        return builder.sign(Algorithm.HMAC256(signingKey))
    }

    private fun configureTestApplication(app: io.ktor.server.application.Application, db: MemoryKeywordUnitOfWork) {
        app.configureSerialization()
        app.configureStatusPages()
        app.configureInternalAuthentication(settings)
        app.routing {
            keywordRoutes(
                DefaultKeywordOperations(db, KeywordLearningDate { LocalDate.of(2026, 9, 24) }),
                KeywordLearningFacts { serverStarted },
            )
        }
    }

    @Test
    fun `토큰이 없거나 기존 Settings 용도면 키워드에 접근할 수 없다`() {
        testApplication {
            environment { config = MapApplicationConfig() }
            val db = MemoryKeywordUnitOfWork()
            application { configureTestApplication(this, db) }
            assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
            for (t in listOf(
                token(use = "ll-internal"),
                token(use = "ll-settings-service"),
                token(signingKey = ByteArray(32) { 99 }),
            )) {
                assertEquals(HttpStatusCode.Unauthorized, client.get(path) { bearerAuth(t) }.status)
            }
            assertTrue(db.learnerIds.isEmpty())
        }
    }

    @Test
    fun `만료되거나 지나치게 긴 키워드 토큰은 거부한다`() {
        testApplication {
            environment { config = MapApplicationConfig() }
            val db = MemoryKeywordUnitOfWork()
            application { configureTestApplication(this, db) }
            for (t in listOf(token(issued = -300), token(ttl = 121))) {
                assertEquals(HttpStatusCode.Unauthorized, client.get(path) { bearerAuth(t) }.status)
            }
            assertTrue(db.learnerIds.isEmpty())
        }
    }

    @Test
    fun `외부 started 주장과 사용자 ID는 LL의 시작 사실과 인증 주체를 바꾸지 못한다`() {
        testApplication {
            // 준비: 학습 시작 사실은 토큰 밖의 LL 조회에서 제공한다.
            environment { config = MapApplicationConfig() }
            val db = MemoryKeywordUnitOfWork()
            serverStarted = true
            application { configureTestApplication(this, db) }

            // 실행: 반대 값의 옛 클레임과 헤더를 주어도 LL 사실을 따른다.
            val response = client.post("$path/custom?userId=999&hasStartedLearning=false") {
                bearerAuth(token(started = false)); header("X-User-Id", "999"); header("X-Learning-Started", "false")
                contentType(ContentType.Application.Json); setBody("""{"text":"IT","type":"TOPIC"}""")
            }

            // 검증: 인증된 사용자에게만 다음 날 예약값이 저장된다.
            assertEquals(HttpStatusCode.Created, response.status)
            val row = db.customRows.values.single()
            assertEquals(123L, row.userId); assertEquals(LocalDate.of(2026, 9, 25), row.pendingEffectiveDate)
        }
    }

    @Test
    fun `BE 토큰에 학습 사실이 없어도 동작하고 옛 true 클레임은 시작으로 간주하지 않는다`() {
        testApplication {
            // 준비
            environment { config = MapApplicationConfig() }
            val db = MemoryKeywordUnitOfWork()
            application { configureTestApplication(this, db) }

            // 실행
            assertEquals(HttpStatusCode.OK, client.get(path) { bearerAuth(token()) }.status)
            val response = client.post("$path/custom") {
                bearerAuth(token(started = true))
                contentType(ContentType.Application.Json)
                setBody("""{"text":"Synthetic","type":"TOPIC"}""")
            }

            // 검증: LL에 시작 이력이 없으므로 즉시 활성화하며 옛 BE 주장은 무시한다.
            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(db.customRows.values.single().active)
            assertEquals(null, db.customRows.values.single().pendingEffectiveDate)
        }
    }

    @Test
    fun `일반 사용자에게 관리자 쓰기를 허용하지 않는다`() {
        testApplication {
            environment { config = MapApplicationConfig() }
            val db = MemoryKeywordUnitOfWork()
            application { configureTestApplication(this, db) }
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post(adminPath) {
                    bearerAuth(token()); contentType(ContentType.Application.Json); setBody(
                    """{"text":"IT","type":"TOPIC"}""",
                )
                }.status,
            )
            assertTrue(db.systemRows.isEmpty())
        }
    }

    @Test
    fun `커스텀 생성 조회 수정 삭제가 기존 응답 13개 필드를 유지한다`() {
        testApplication {
            environment { config = MapApplicationConfig() }
            val db = MemoryKeywordUnitOfWork()
            application { configureTestApplication(this, db) }
            val created = client.post("$path/custom") {
                bearerAuth(token()); contentType(ContentType.Application.Json); setBody(
                """{"text":"IT","type":"TOPIC"}""",
            )
            }
            assertEquals(HttpStatusCode.Created, created.status)
            val json = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            assertEquals(13, json.size); assertEquals(JsonNull, json["pendingEffectiveDate"])
            val id = json.getValue("id").jsonPrimitive.long
            assertEquals(
                HttpStatusCode.OK,
                client.patch("$path/custom/$id") {
                    bearerAuth(token()); contentType(ContentType.Application.Json); setBody("""{"text":"Business"}""")
                }.status,
            )
            val list = Json.parseToJsonElement(client.get(path) { bearerAuth(token()) }.bodyAsText()).jsonObject
            assertEquals(
                "Business",
                list.getValue("customKeywords").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content,
            )
            assertEquals(HttpStatusCode.OK, client.delete("$path/custom/$id") { bearerAuth(token()) }.status)
            assertEquals(false, db.customRows.getValue(id).active)
        }
    }

    @Test
    fun `잘못된 JSON 유형 미지정 유형 알 수 없는 필드를 거부한다`() {
        testApplication {
            environment { config = MapApplicationConfig() }
            val db = MemoryKeywordUnitOfWork()
            application { configureTestApplication(this, db) }
            for (body in listOf(
                "{",
                "[]",
                """{"text":"IT"}""",
                """{"text":"IT","type":"OTHER"}""",
                """{"text":"IT","type":"TOPIC","userId":999}""",
            )) {
                assertEquals(
                    HttpStatusCode.BadRequest,
                    client.post("$path/custom") {
                        bearerAuth(token()); contentType(ContentType.Application.Json); setBody(body)
                    }.status,
                )
            }
            assertTrue(db.customRows.isEmpty())
        }
    }

    @Test
    fun `후보 응답은 예약값이 아닌 오늘의 활성값이며 날짜는 필수다`() {
        testApplication {
            environment { config = MapApplicationConfig() }
            val db = MemoryKeywordUnitOfWork()
            serverStarted = true
            application { configureTestApplication(this, db) }
            client.post("$path/custom") {
                bearerAuth(token(started = true)); contentType(ContentType.Application.Json); setBody(
                """{"text":"IT","type":"TOPIC"}""",
            )
            }
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("$path/candidates") { bearerAuth(token(started = true)) }.status,
            )
            val current = client.get("$path/candidates?learningDate=2026-09-24") { bearerAuth(token(started = true)) }
            assertTrue(
                Json.parseToJsonElement(current.bodyAsText()).jsonObject.getValue("candidates").jsonArray.isEmpty(),
            )
            val next = client.get("$path/candidates?learningDate=2026-09-25") { bearerAuth(token(started = true)) }
            assertEquals(1, Json.parseToJsonElement(next.bodyAsText()).jsonObject.getValue("candidates").jsonArray.size)
        }
    }

    @Test
    fun `관리자 카탈로그 생성과 시스템 선택은 동일 ID를 사용한다`() {
        testApplication {
            environment { config = MapApplicationConfig() }
            val db = MemoryKeywordUnitOfWork()
            application { configureTestApplication(this, db) }
            val created = client.post(adminPath) {
                bearerAuth(
                    token(
                        user = 900,
                        admin = true,
                    ),
                ); contentType(ContentType.Application.Json); setBody("""{"text":"IT","type":"TOPIC"}""")
            }
            assertEquals(HttpStatusCode.Created, created.status)
            val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.long
            assertEquals(
                HttpStatusCode.OK,
                client.put("$path/system/$id/selection") {
                    bearerAuth(token()); contentType(ContentType.Application.Json); setBody("""{"selected":true}""")
                }.status,
            )
            assertEquals(id, db.selectionRows.values.single().systemKeywordId)
            assertEquals(
                HttpStatusCode.BadRequest,
                client.delete("$path/custom/not-a-number") { bearerAuth(token()) }.status,
            )
        }
    }
}
