package jp.co.translacat.languagelearning.features.settings.infrastructure.persistence

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
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskType.DICTATION
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskType.SUMMARY
import jp.co.translacat.languagelearning.features.settings.api.settingsRoutes
import jp.co.translacat.languagelearning.features.settings.api.settingsSelectionRoute
import jp.co.translacat.languagelearning.features.settings.api.settingsServiceRoutes
import jp.co.translacat.languagelearning.features.settings.application.*
import jp.co.translacat.languagelearning.features.settings.domain.model.SelectionDeliveryStatus.*
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettingsChange
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.shared.security.InternalApiSettings
import jp.co.translacat.languagelearning.support.CurrentSchema
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

/** LL_TEST_MYSQL_*의 loopback 임시 DB만 사용한다. 기존 LL/Core DB에는 접속하지 않는다. */
class SettingsCutoverIntegrationTest {
    private val instant = Instant.parse("2026-09-24T00:30:00Z")
    private val clock = Clock.fixed(instant, ZoneOffset.UTC)

    private class Services(factory: DatabaseFactory, clock: Clock) {
        val runner = JdbcTransactionRunner(factory.database, 4)
        val work = ExposedSettingsUnitOfWork(runner, clock)
        val queries = ExposedSettingsReadQueries(runner)
        val reads = DefaultSettingsServiceOperations(
            work, queries, GetAdminSettings(ExposedAdminSettingsUnitOfWork(runner, clock)), clock,
        )
        val relay = RememberListeningSelection(ExposedSelectionSettingsUnitOfWork(work))
        val update = UpdateUserSettings(work)
        val operations = DefaultSettingsOperations(
            GetUserSettings(work),
            update,
            GetAdminSettings(ExposedAdminSettingsUnitOfWork(runner, clock)),
            UpdateAdminSettings(ExposedAdminSettingsUnitOfWork(runner, clock)),
        )

        suspend fun configured(userId: Long = 123) =
            update.execute(userId, UserSettingsChange(originLanguage = "ko", learningLanguage = "ja")).settings
    }

    private fun db(block: (LocalScratchMysql, DatabaseFactory, Services) -> Unit) = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory -> block(db, factory, Services(factory, clock)) }
    }

    @Test
    fun `빈 DB의 날짜 조회는 행이나 정책을 생성하지 않는다`() = db { db, _, s ->
        runBlocking {
            assertEquals(LocalDate.of(2026, 9, 24), s.reads.learningDate(123))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_learner"))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_user_setting"))
        }
    }

    @Test
    fun `수동 날짜 조회와 snapshot 승격의 동작을 구분한다`() = db { db, _, s ->
        runBlocking {
            s.configured()
            sql(
                db,
                "UPDATE language_learning_user_setting SET pending_timezone='America/New_York', pending_effective_date='2026-09-24' WHERE user_id=123",
            )
            assertEquals(LocalDate.of(2026, 9, 24), s.reads.learningDate(123))
            assertEquals(
                "America/New_York",
                string(db, "SELECT pending_timezone FROM language_learning_user_setting WHERE user_id=123"),
            )
            val snapshot = s.reads.userSnapshot(123)
            assertEquals(LocalDate.of(2026, 9, 23), snapshot.learningDate)
            assertEquals("America/New_York", snapshot.result.settings.timezone)
            assertNull(snapshot.result.settings.pendingEffectiveDate)
        }
    }

    @Test
    fun `언어쌍은 DISTINCT 활성 언어이며 pending을 승격하지 않는다`() = db { db, _, s ->
        runBlocking {
            s.configured(123); s.configured(456); GetUserSettings(s.work).execute(789)
            sql(
                db,
                "UPDATE language_learning_user_setting SET pending_learning_language='en', pending_effective_date='2000-01-01' WHERE user_id=123",
            )
            val pairs = s.reads.configuredLanguagePairs()
            assertEquals(1, pairs.size); assertEquals("ko", pairs.single().originLanguage); assertEquals(
            "ja", pairs.single().learningLanguage,
        )
            assertEquals(
                "en",
                string(db, "SELECT pending_learning_language FROM language_learning_user_setting WHERE user_id=123"),
            )
        }
    }

    @Test
    fun `Listening 정책은 원격 조회마다 최신 DB 값을 반환한다`() = db { db, _, s ->
        runBlocking {
            assertEquals(5, s.reads.listeningPolicy().defaultItemCount)
            sql(db, "UPDATE language_learning_listening_policy_setting SET default_item_count=8 WHERE id='DEFAULT'")
            assertEquals(8, s.reads.listeningPolicy().defaultItemCount)
            assertEquals("listening-profile", s.reads.listeningPolicy().profilePolicyVersion)
        }
    }

    @Test
    fun `같은 전달과 역순 전달은 데이터와 revision을 덮어쓰지 않는다`() = db { db, _, s ->
        runBlocking {
            val base = s.configured().updatedAt
            assertEquals(APPLIED, s.relay.execute(123, 11, base, listOf(SUMMARY)))
            val first = s.reads.userSnapshot(123).result.settings
            assertEquals(DUPLICATE, s.relay.execute(123, 11, base, listOf(DICTATION)))
            assertEquals(DUPLICATE, s.relay.execute(123, 10, base, listOf(DICTATION)))
            assertEquals(first, s.reads.userSnapshot(123).result.settings)
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM language_learning_settings_selection_delivery"))
        }
    }

    @Test
    fun `동일 Task의 직접 PATCH도 오래된 선택 전달을 차단한다`() = db { _, _, s ->
        runBlocking {
            val base = s.configured().updatedAt
            s.update.execute(123, UserSettingsChange(defaultListeningTaskTypes = listOf(DICTATION)))
            assertEquals(SUPERSEDED, s.relay.execute(123, 11, base, listOf(SUMMARY)))
            assertEquals("[\"DICTATION\"]", s.reads.userSnapshot(123).result.settings.defaultListeningTaskTypesJson)
        }
    }

    @Test
    fun `같은 base의 동시 이벤트는 source ID가 큰 최종 선택으로 수렴한다`() = db { db, _, s ->
        DatabaseFactory(db.settings()).use { second ->
            runBlocking {
                withTimeout(30_000) {
                    val other = Services(second, clock);
                    val base = s.configured().updatedAt
                    listOf(
                        async { s.relay.execute(123, 10, base, listOf(DICTATION)) },
                        async { other.relay.execute(123, 11, base, listOf(SUMMARY)) },
                    ).awaitAll()
                    assertEquals(
                        "[\"SUMMARY\"]", s.reads.userSnapshot(123).result.settings.defaultListeningTaskTypesJson,
                    )
                    assertEquals(
                        11L,
                        scalar(
                            db,
                            "SELECT last_event_id FROM language_learning_settings_selection_delivery WHERE user_id=123",
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `수신 저장소 실패는 앞선 pending 승격까지 롤백한다`() = db { db, _, s ->
        runBlocking {
            val base = s.configured().updatedAt
            sql(
                db,
                "UPDATE language_learning_user_setting SET pending_daily_sentence_count=7, pending_effective_date='2000-01-01' WHERE user_id=123",
            )
            // 이 테스트가 소유한 임시 DB의 수신 테이블에만 실패를 주입한다.
            sql(db, "DROP TABLE language_learning_settings_selection_delivery")
            assertFails { s.relay.execute(123, 10, base, listOf(SUMMARY)) }
            assertEquals(
                5L, scalar(db, "SELECT daily_sentence_count FROM language_learning_user_setting WHERE user_id=123"),
            )
            assertEquals(
                7L,
                scalar(db, "SELECT pending_daily_sentence_count FROM language_learning_user_setting WHERE user_id=123"),
            )
        }
    }

    @Test
    fun `V003에서 최신 버전으로 올려도 관리자 변경값과 감사 데이터가 보존된다`() = LocalScratchMysql.use { db ->
        val settings = db.settings()
        Flyway.configure()
            .dataSource(settings.jdbcUrl, settings.username, settings.password)
            .locations("classpath:db/migration")
            .defaultSchema(db.name)
            .schemas(db.name)
            .createSchemas(false)
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .target("003")
            .load()
            .migrate()
        sql(db, "UPDATE language_learning_admin_setting SET daily_keyword_max_count=6 WHERE id='DEFAULT'")
        sql(
            db,
            "INSERT INTO language_learning_admin_setting_audit(admin_user_id,before_json,after_json,created_at) VALUES(900,'{}','{}',UTC_TIMESTAMP(6))",
        )
        DatabaseFactory(settings).use { factory ->
            assertEquals(CurrentSchema.VERSION - 3, factory.migrationReport.migrationsExecuted); assertEquals(
            CurrentSchema.VERSION, factory.migrationReport.schemaVersion.toInt(),
        )
            assertEquals(
                6L,
                scalar(db, "SELECT daily_keyword_max_count FROM language_learning_admin_setting WHERE id='DEFAULT'"),
            )
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM language_learning_admin_setting_audit"))
        }
    }

    @Test
    fun `서비스 JWT 조회와 사용자 outbox 전달이 HTTP에서 분리된다`() = db { db, _, s ->
        testApplication {
            environment { config = MapApplicationConfig() }
            val key = ByteArray(32) { (it + 1).toByte() }
            val auth = InternalApiSettings(enabled = true, secretBase64 = Base64.getEncoder().encodeToString(key))
            application {
                configureSerialization(); configureStatusPages(); configureInternalAuthentication(auth)
                routing {
                    settingsRoutes(s.operations); settingsServiceRoutes(s.reads); settingsSelectionRoute(
                    s.relay,
                )
                }
            }
            val userToken = token(key, false);
            val serviceToken = token(key, true)
            val servicePath = "/internal/v1/service/language-learning/settings/users/123"
            assertEquals(HttpStatusCode.Unauthorized, client.get(servicePath) { bearerAuth(userToken) }.status)
            val get = client.get(servicePath) { bearerAuth(serviceToken) }
            assertEquals(HttpStatusCode.OK, get.status)
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM language_learning_learner"))
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.patch("/internal/v1/admin/language-learning/settings") {
                    bearerAuth(serviceToken); contentType(ContentType.Application.Json); setBody("{}")
                }.status,
            )
            s.configured()
            val snapshot = client.get(servicePath) { bearerAuth(serviceToken) }
            val revision =
                Json.parseToJsonElement(snapshot.bodyAsText()).jsonObject.getValue("revision").jsonPrimitive.content
            val payload = """{"eventId":100,"expectedRevision":"$revision","taskTypes":["SUMMARY"]}"""
            val delivery = client.post("/internal/v1/language-learning/settings/listening-selection") {
                bearerAuth(userToken); contentType(ContentType.Application.Json); setBody(payload)
            }
            assertEquals(HttpStatusCode.OK, delivery.status)
            assertEquals(
                "APPLIED",
                Json.parseToJsonElement(delivery.bodyAsText()).jsonObject.getValue("status").jsonPrimitive.content,
            )
            assertEquals(
                "[\"SUMMARY\"]",
                string(db, "SELECT default_listening_task_types FROM language_learning_user_setting WHERE user_id=123"),
            )
        }
    }

    private fun token(key: ByteArray, service: Boolean): String {
        val now = Instant.now()
        val builder = JWT.create()
            .withIssuer("translacat-be")
            .withAudience("translacat-ll")
            .withSubject(if (service) "translacat-be" else "123")
            .withClaim("service", "translacat-be")
            .withClaim("tokenUse", if (service) "ll-settings-service" else "ll-internal")
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(120)))
        if (service) builder.withArrayClaim("scopes", arrayOf("settings:read"))
        else builder.withArrayClaim("roles", arrayOf("USER"))
        return builder.sign(Algorithm.HMAC256(key))
    }

    private fun sql(db: LocalScratchMysql, value: String) =
        db.connect().use { it.createStatement().use { s -> s.executeUpdate(value) } }

    private fun scalar(db: LocalScratchMysql, value: String): Long = db.connect()
        .use { it.createStatement().use { s -> s.executeQuery(value).use { r -> check(r.next()); r.getLong(1) } } }

    private fun string(db: LocalScratchMysql, value: String): String = db.connect()
        .use { it.createStatement().use { s -> s.executeQuery(value).use { r -> check(r.next()); r.getString(1) } } }
}
