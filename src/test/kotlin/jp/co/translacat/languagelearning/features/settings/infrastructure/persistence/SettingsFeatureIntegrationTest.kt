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
import jp.co.translacat.languagelearning.features.learner.domain.exception.LearnerUnavailableException
import jp.co.translacat.languagelearning.features.settings.api.settingsRoutes
import jp.co.translacat.languagelearning.features.settings.application.*
import jp.co.translacat.languagelearning.features.settings.domain.model.AdminSettingsChange
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettingsChange
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.*
import kotlin.test.*

/** 실제 loopback MySQL의 별도 임시 DB에서만 실행한다. 앱 DB를 입력받지 않는다. */
class SettingsFeatureIntegrationTest {
    private val now = Instant.parse("2026-09-24T03:30:00.123456Z")
    private fun operations(factory: DatabaseFactory, at: Instant = now): SettingsOperations {
        val runner = JdbcTransactionRunner(factory.database, 4);
        val clock = Clock.fixed(at, ZoneOffset.UTC)
        val work = ExposedSettingsUnitOfWork(runner, clock);
        val admin = ExposedAdminSettingsUnitOfWork(runner, clock)
        return DefaultSettingsOperations(
            GetUserSettings(work), UpdateUserSettings(work), GetAdminSettings(admin), UpdateAdminSettings(admin),
        )
    }

    @Test
    fun `빈 DB에서 최초 설정과 예약 및 다음날 승격이 실제 저장된다`() = db { db, factory ->
        runBlocking {
            val ops = operations(factory)
            assertFalse(ops.getUser(123).settings.configured)
            val first = ops.updateUser(
                123, UserSettingsChange(originLanguage = "ko", learningLanguage = "ja", dailySentenceCount = 7),
            ).settings
            assertTrue(first.configured); assertEquals(
            7, first.dailySentenceCount,
        ); assertNull(first.pendingEffectiveDate)
            val pending = ops.updateUser(
                123,
                UserSettingsChange(learningLanguage = "en", dailySentenceCount = 9, speakingPlaybackSpeed = "slow"),
            ).settings
            assertEquals("ja", pending.learningLanguage); assertEquals("en", pending.pendingLearningLanguage)
            assertEquals("SLOW", pending.speakingPlaybackSpeed); assertEquals(
            LocalDate.of(2026, 9, 25), pending.pendingEffectiveDate,
        )
            assertEquals(pending, ops.getUser(123).settings)
            val after = operations(factory, Instant.parse("2026-09-24T15:00:00Z")).getUser(123).settings
            assertEquals("en", after.learningLanguage); assertEquals(
            9, after.dailySentenceCount,
        ); assertNull(after.pendingEffectiveDate)
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM language_learning_user_setting"))
        }
    }

    @Test
    fun `최초 잘못된 PATCH는 learner도 남기지 않는다`() = db { db, factory ->
        runBlocking {
            assertFailsWith<LearningBusinessException> {
                operations(factory).updateUser(
                    123, UserSettingsChange(originLanguage = "ko"),
                )
            }
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_learner"))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_user_setting"))
        }
    }

    @Test
    fun `잘못된 PATCH는 앞에서 수행한 승격과 감사 시각 변경을 롤백한다`() = db { db, factory ->
        runBlocking {
            val ops = operations(factory); ops.updateUser(
            123, UserSettingsChange(originLanguage = "ko", learningLanguage = "ja"),
        )
            sql(
                db,
                "UPDATE language_learning_user_setting SET pending_daily_sentence_count=8,pending_effective_date='2000-01-01' WHERE user_id=123",
            )
            assertFailsWith<LearningBusinessException> {
                ops.updateUser(
                    123, UserSettingsChange(dailySentenceCount = 999),
                )
            }
            assertEquals(
                8L,
                scalar(db, "SELECT pending_daily_sentence_count FROM language_learning_user_setting WHERE user_id=123"),
            )
            assertEquals(
                "2000-01-01",
                string(db, "SELECT pending_effective_date FROM language_learning_user_setting WHERE user_id=123"),
            )
            assertEquals(
                5L, scalar(db, "SELECT daily_sentence_count FROM language_learning_user_setting WHERE user_id=123"),
            )
        }
    }

    @Test
    fun `저장 Task JSON이 손상된 GET은 pending 승격을 커밋하지 않는다`() = db { db, factory ->
        runBlocking {
            val ops = operations(factory)
            ops.updateUser(123, UserSettingsChange(originLanguage = "ko", learningLanguage = "ja"))
            sql(
                db,
                "UPDATE language_learning_user_setting SET default_listening_task_types='not-json', pending_daily_sentence_count=8, pending_effective_date='2000-01-01' WHERE user_id=123",
            )
            assertFails { ops.getUser(123) }
            assertEquals(
                5L, scalar(db, "SELECT daily_sentence_count FROM language_learning_user_setting WHERE user_id=123"),
            )
            assertEquals(
                8L,
                scalar(db, "SELECT pending_daily_sentence_count FROM language_learning_user_setting WHERE user_id=123"),
            )
        }
    }

    @Test
    fun `관리자 범위 변경은 조회 시 활성과 pending 모두 보정한다`() = db { db, factory ->
        runBlocking {
            val ops = operations(factory); ops.updateUser(
            123, UserSettingsChange(originLanguage = "ko", learningLanguage = "ja", dailySentenceCount = 18),
        )
            ops.updateUser(123, UserSettingsChange(dailySentenceCount = 20))
            ops.updateAdmin(900, AdminSettingsChange(maxDailySentenceCount = 10))
            val result = ops.getUser(123)
            assertEquals(10, result.settings.dailySentenceCount); assertEquals(
            10, result.settings.pendingDailySentenceCount,
        )
            assertEquals(10, result.policy.writing.maximum)
            assertEquals(
                10L, scalar(db, "SELECT daily_sentence_count FROM language_learning_user_setting WHERE user_id=123"),
            )
        }
    }

    @Test
    fun `관리자 기본값 변경은 새 사용자에게만 적용한다`() = db { _, factory ->
        runBlocking {
            val ops = operations(factory); ops.getUser(123); ops.updateAdmin(
            900, AdminSettingsChange(defaultDailySentenceCount = 7),
        )
            assertEquals(5, ops.getUser(123).settings.dailySentenceCount); assertEquals(
            7, ops.getUser(456).settings.dailySentenceCount,
        )
        }
    }

    @Test
    fun `관리자 변경 감사는 주체와 before after의 30개 필드를 보존한다`() = db { db, factory ->
        runBlocking {
            val ops = operations(factory); ops.updateAdmin(900, AdminSettingsChange(dailyKeywordMaxCount = 8))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM language_learning_admin_setting_audit"))
            assertEquals(900L, scalar(db, "SELECT admin_user_id FROM language_learning_admin_setting_audit"))
            val before = Json.parseToJsonElement(
                string(
                    db, "SELECT before_json FROM language_learning_admin_setting_audit",
                ),
            ).jsonObject
            val after = Json.parseToJsonElement(
                string(
                    db, "SELECT after_json FROM language_learning_admin_setting_audit",
                ),
            ).jsonObject
            assertEquals(30, before.size); assertEquals(30, after.size)
            assertEquals(5, before.getValue("dailyKeywordMaxCount").jsonPrimitive.int)
            assertEquals(8, after.getValue("dailyKeywordMaxCount").jsonPrimitive.int)
            assertEquals("900", string(db, "SELECT updated_by FROM language_learning_admin_setting WHERE id='DEFAULT'"))
        }
    }

    @Test
    fun `빈 관리자 PATCH도 원본처럼 감사를 한 번 기록한다`() = db { db, factory ->
        runBlocking {
            operations(factory).updateAdmin(900, AdminSettingsChange())
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM language_learning_admin_setting_audit"))
            assertEquals(
                string(db, "SELECT before_json FROM language_learning_admin_setting_audit"),
                string(db, "SELECT after_json FROM language_learning_admin_setting_audit"),
            )
        }
    }

    @Test
    fun `감사 기록 실패는 관리자 설정 변경도 롤백한다`() = db { db, factory ->
        runBlocking {
            // 이 테스트가 만든 임시 DB의 감사 테이블만 제거해 저장 실패를 재현한다.
            sql(db, "DROP TABLE language_learning_admin_setting_audit")
            assertFails { operations(factory).updateAdmin(900, AdminSettingsChange(dailyKeywordMaxCount = 8)) }
            assertEquals(
                5L,
                scalar(db, "SELECT daily_keyword_max_count FROM language_learning_admin_setting WHERE id='DEFAULT'"),
            )
        }
    }

    @Test
    fun `동시 관리자 부분 PATCH는 갱신 유실 없이 직렬화되고 감사가 연결된다`() = db { db, firstFactory ->
        DatabaseFactory(db.settings()).use { secondFactory ->
            runBlocking {
                withTimeout(30_000) {
                    val a = operations(firstFactory);
                    val b = operations(secondFactory)
                    listOf(
                        async { a.updateAdmin(900, AdminSettingsChange(dailyKeywordMaxCount = 8)) },
                        async { b.updateAdmin(901, AdminSettingsChange(reviewAvailableDays = 10)) },
                    ).awaitAll()
                    val value = a.getAdmin(); assertEquals(8, value.dailyKeywordMaxCount); assertEquals(
                    10, value.reviewAvailableDays,
                )
                    assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM language_learning_admin_setting_audit"))
                    assertEquals(
                        string(db, "SELECT after_json FROM language_learning_admin_setting_audit ORDER BY id LIMIT 1"),
                        string(
                            db,
                            "SELECT before_json FROM language_learning_admin_setting_audit ORDER BY id LIMIT 1 OFFSET 1",
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `동시 사용자 부분 PATCH는 서로의 pending을 잃지 않는다`() = db { db, factory ->
        DatabaseFactory(db.settings()).use { second ->
            runBlocking {
                withTimeout(30_000) {
                    val a = operations(factory);
                    val b = operations(second)
                    a.updateUser(123, UserSettingsChange(originLanguage = "ko", learningLanguage = "ja"))
                    listOf(
                        async { a.updateUser(123, UserSettingsChange(dailySentenceCount = 8)) },
                        async { b.updateUser(123, UserSettingsChange(dailyListeningGoalCount = 9)) },
                    ).awaitAll()
                    val value = a.getUser(123).settings
                    assertEquals(8, value.pendingDailySentenceCount); assertEquals(
                    9, value.pendingDailyListeningGoalCount,
                )
                }
            }
        }
    }

    @Test
    fun `정지한 learner는 GET PATCH 모두 데이터 변경 없이 거부한다`() = db { db, factory ->
        runBlocking {
            val ops = operations(factory); ops.getUser(123)
            sql(db, "UPDATE language_learning_learner SET status='SUSPENDED' WHERE user_id=123")
            assertFailsWith<LearnerUnavailableException> { ops.getUser(123) }
            assertFailsWith<LearnerUnavailableException> {
                ops.updateUser(
                    123, UserSettingsChange(originLanguage = "ko", learningLanguage = "ja"),
                )
            }
            assertEquals("SUSPENDED", string(db, "SELECT status FROM language_learning_learner WHERE user_id=123"))
        }
    }

    @Test
    fun `잘못된 관리자 업데이트는 설정과 감사 모두 변경하지 않는다`() = db { db, factory ->
        runBlocking {
            val ops = operations(factory);
            val before = ops.getAdmin()
            assertFailsWith<LearningBusinessException> {
                ops.updateAdmin(
                    900, AdminSettingsChange(maxDailySentenceCount = 2),
                )
            }
            assertEquals(before, ops.getAdmin()); assertEquals(
            0L, scalar(db, "SELECT COUNT(*) FROM language_learning_admin_setting_audit"),
        )
        }
    }

    @Test
    fun `V002 DB를 최신 버전으로 올려도 기존 사용자 설정을 보존한다`() = LocalScratchMysql.use { db ->
        val settings = db.settings()
        val migrated = Flyway.configure()
            .dataSource(settings.jdbcUrl, settings.username, settings.password)
            .locations("classpath:db/migration")
            .defaultSchema(db.name)
            .schemas(db.name)
            .createSchemas(false)
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .target("002")
            .load()
            .migrate()
        assertEquals(2, migrated.migrationsExecuted)
        sql(db, "UPDATE language_learning_admin_setting SET default_daily_sentence_count=7 WHERE id='DEFAULT'")
        sql(
            db,
            "INSERT INTO language_learning_learner(user_id,created_at,updated_at) VALUES(123,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        )
        DatabaseFactory(settings).use { factory ->
            assertEquals(CurrentSchema.VERSION - 2, factory.migrationReport.migrationsExecuted); assertEquals(
            CurrentSchema.VERSION, factory.migrationReport.schemaVersion.toInt(),
        )
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM language_learning_learner"))
            assertEquals(
                7L,
                scalar(
                    db, "SELECT default_daily_sentence_count FROM language_learning_admin_setting WHERE id='DEFAULT'",
                ),
            )
        }
        DatabaseFactory(settings).use { assertEquals(0, it.migrationReport.migrationsExecuted) }
    }

    @Test
    fun `실제 HTTP 인증부터 개인 설정 DB 저장까지 연결된다`() = db { db, factory ->
        testApplication {
            environment { config = MapApplicationConfig() }
            val secret = ByteArray(32) { (it + 1).toByte() }
            val auth = InternalApiSettings(
                enabled = true, secretBase64 = Base64.getEncoder().encodeToString(secret),
            )
            application {
                configureSerialization(); configureStatusPages(); configureInternalAuthentication(auth); routing {
                settingsRoutes(
                    operations(factory),
                )
            }
            }
            val path = "/internal/v1/language-learning/settings"
            assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_learner"))
            val token = internalToken(secret, 123, false)
            assertEquals(HttpStatusCode.OK, client.get(path) { bearerAuth(token) }.status)
            assertEquals(
                HttpStatusCode.OK,
                client.patch(path) {
                    bearerAuth(token); contentType(ContentType.Application.Json)
                    setBody("""{"originLanguage":"ko","learningLanguage":"ja"}""")
                }.status,
            )
            val result = client.patch(path) {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody("""{"dailySentenceCount":9,"speakingPlaybackSpeed":"slow"}""")
            }
            assertEquals(HttpStatusCode.OK, result.status)
            val body = Json.parseToJsonElement(result.bodyAsText()).jsonObject
            assertEquals("2026-09-25", body.getValue("pendingEffectiveDate").jsonPrimitive.content)
            assertEquals(
                9L,
                scalar(db, "SELECT pending_daily_sentence_count FROM language_learning_user_setting WHERE user_id=123"),
            )
            assertEquals(
                "SLOW",
                string(db, "SELECT speaking_playback_speed FROM language_learning_user_setting WHERE user_id=123"),
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                client.patch(path) {
                    bearerAuth(token); contentType(ContentType.Application.Json)
                    setBody("""{"defaultListeningTaskTypes":["INTERPRETATION"]}""")
                }.status,
            )
        }
    }

    @Test
    fun `관리자 HTTP 권한과 감사 저장은 연결되고 일반 사용자는 수정할 수 없다`() = db { db, factory ->
        testApplication {
            environment { config = MapApplicationConfig() }
            val secret = ByteArray(32) { (it + 1).toByte() }
            val auth = InternalApiSettings(
                enabled = true, secretBase64 = Base64.getEncoder().encodeToString(secret),
            )
            application {
                configureSerialization(); configureStatusPages(); configureInternalAuthentication(auth); routing {
                settingsRoutes(
                    operations(factory),
                )
            }
            }
            val path = "/internal/v1/admin/language-learning/settings"
            assertEquals(
                HttpStatusCode.Forbidden,
                client.patch(path) {
                    bearerAuth(internalToken(secret, 900, false)); contentType(ContentType.Application.Json)
                    setBody("""{"dailyKeywordMaxCount":8}""")
                }.status,
            )
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_admin_setting_audit"))
            assertEquals(
                HttpStatusCode.OK,
                client.patch(path) {
                    bearerAuth(internalToken(secret, 900, true)); contentType(ContentType.Application.Json)
                    setBody("""{"dailyKeywordMaxCount":8}""")
                }.status,
            )
            assertEquals(900L, scalar(db, "SELECT admin_user_id FROM language_learning_admin_setting_audit"))
        }
    }

    private fun internalToken(secret: ByteArray, userId: Long, admin: Boolean): String {
        val instant = Instant.now()
        return JWT.create()
            .withIssuer("translacat-be")
            .withAudience("translacat-ll")
            .withSubject(userId.toString())
            .withClaim("service", "translacat-be")
            .withClaim("tokenUse", "ll-internal")
            .withArrayClaim("roles", arrayOf(if (admin) "ADMIN" else "USER"))
            .withIssuedAt(Date.from(instant))
            .withExpiresAt(Date.from(instant.plusSeconds(60)))
            .sign(Algorithm.HMAC256(secret))
    }

    private fun db(action: (LocalScratchMysql, DatabaseFactory) -> Unit) = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { action(db, it) }
    }

    private fun sql(db: LocalScratchMysql, value: String) {
        db.connect().use {
            it.createStatement().use { s -> s.executeUpdate(value) }
        }
    }

    private fun scalar(db: LocalScratchMysql, value: String): Long = db.connect().use {
        it.createStatement().use { s ->
            s.executeQuery(value).use { r -> check(r.next()); r.getLong(1) }
        }
    }

    private fun string(db: LocalScratchMysql, value: String): String = db.connect().use {
        it.createStatement().use { s ->
            s.executeQuery(value).use { r -> check(r.next()); r.getString(1) }
        }
    }
}
