package jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence

import jp.co.translacat.languagelearning.features.keyword.application.DefaultKeywordOperations
import jp.co.translacat.languagelearning.features.keyword.application.KeywordLearningDate
import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordChange
import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordType
import jp.co.translacat.languagelearning.features.learner.domain.exception.LearnerUnavailableException
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.flywaydb.core.Flyway
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 일반 test에서는 제외한다. LL_TEST_MYSQL_*의 loopback 임시 DB만 사용한다. */
class KeywordCatalogIntegrationTest {
    private val today = LocalDate.of(2026, 9, 24)
    private val clock = Clock.fixed(Instant.parse("2026-09-24T03:00:00Z"), ZoneOffset.UTC)
    private fun service(factory: DatabaseFactory) = DefaultKeywordOperations(
        ExposedKeywordUnitOfWork(JdbcTransactionRunner(factory.database, 4), clock), KeywordLearningDate { today },
    )
    private fun db(block: (LocalScratchMysql, DatabaseFactory, DefaultKeywordOperations) -> Unit) {
        LocalScratchMysql.use { db -> DatabaseFactory(db.settings()).use { factory -> block(db, factory, service(factory)) } }
    }
    private fun sql(db: LocalScratchMysql, sql: String) { db.connect().use { connection -> connection.createStatement().use { it.executeUpdate(sql) } } }
    private fun number(db: LocalScratchMysql, sql: String): Long = db.connect().use { connection ->
        connection.createStatement().use { it.executeQuery(sql).use { row -> check(row.next()); row.getLong(1) } }
    }

    @Test fun `V004에서 V005로 올려도 Settings와 수신 이력을 변경하지 않는다`() {
        LocalScratchMysql.use { db ->
            val settings = db.settings()
            Flyway.configure().dataSource(settings.jdbcUrl, settings.username, settings.password)
                .locations("classpath:db/migration").schemas(db.name).defaultSchema(db.name)
                .createSchemas(false).cleanDisabled(true).baselineOnMigrate(false).target("004").load().migrate()
            sql(db, "UPDATE language_learning_admin_setting SET daily_keyword_max_count=6 WHERE id='DEFAULT'")
            sql(db, "INSERT INTO language_learning_learner(user_id,status,identity_version,created_at,updated_at) VALUES(123,'ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")
            sql(db, "INSERT INTO language_learning_settings_selection_delivery(user_id,last_event_id,base_revision,applied_revision) VALUES(123,99,UTC_TIMESTAMP(6),NULL)")
            DatabaseFactory(settings).use { factory ->
                assertEquals(1, factory.migrationReport.migrationsExecuted)
                assertEquals(5, factory.migrationReport.schemaVersion.toInt())
            }
            assertEquals(6L, number(db, "SELECT daily_keyword_max_count FROM language_learning_admin_setting WHERE id='DEFAULT'"))
            assertEquals(99L, number(db, "SELECT last_event_id FROM language_learning_settings_selection_delivery WHERE user_id=123"))
            assertEquals(12L, number(db, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"))
            DatabaseFactory(settings).use { assertEquals(0, it.migrationReport.migrationsExecuted) }
        }
    }
    @Test fun `빈 DB에는 키워드 데이터가 없고 생성 및 예약 승격을 재조회한다`() {
        db { db, _, ops -> runBlocking {
            assertEquals(0L, number(db, "SELECT COUNT(*) FROM language_learning_system_keyword"))
            val row = ops.createCustom(123, true, KeywordChange(text = "IT", type = KeywordType.TOPIC))
            assertEquals(today.plusDays(1), row.pendingEffectiveDate)
            assertTrue(ops.candidates(123, true, today).isEmpty())
            assertEquals("IT", ops.candidates(123, true, today.plusDays(1)).single().text)
            assertEquals(1L, number(db, "SELECT COUNT(*) FROM language_learning_custom_keyword WHERE pending_effective_date IS NULL AND active=1"))
            assertEquals(1L, number(db, "SELECT COUNT(*) FROM language_learning_custom_keyword WHERE created_by='123' AND updated_by='123'"))
        } }
    }
    @Test fun `두 pool에서 동시에 같은 사용자 키워드를 생성해도 한 행만 남는다`() {
        db { db, _, ops -> DatabaseFactory(db.settings()).use { other -> runBlocking {
            val second = service(other)
            val successes = withTimeout(30_000) {
                (0 until 16).map { index -> async {
                    try { (if (index % 2 == 0) ops else second).createCustom(123, false, KeywordChange(text = "IT", type = KeywordType.TOPIC)); true }
                    catch (error: LearningBusinessException) { assertEquals("KEYWORD_DUPLICATED", error.code); false }
                } }.awaitAll()
            }
            assertEquals(1, successes.count { it })
            assertEquals(1L, number(db, "SELECT COUNT(*) FROM language_learning_custom_keyword"))
            assertEquals(1L, number(db, "SELECT COUNT(*) FROM language_learning_learner"))
        } } }
    }
    @Test fun `동시 시스템 선택은 사용자당 한 행을 유지한다`() {
        db { db, _, ops -> DatabaseFactory(db.settings()).use { other -> runBlocking {
            val id = ops.createSystem(900, KeywordChange(text = "IT", type = KeywordType.TOPIC)).id
            val second = service(other)
            withTimeout(30_000) { (0 until 16).map { i -> async {
                (if (i % 2 == 0) ops else second).selectSystem(123, false, id, true)
            } }.awaitAll() }
            assertEquals(1L, number(db, "SELECT COUNT(*) FROM language_learning_user_system_keyword"))
            assertEquals(1, ops.candidates(123, false, today).size)
        } } }
    }
    @Test fun `DB collation unique 위반은 업무 중복 오류로 변환한다`() {
        db { db, _, ops -> runBlocking {
            ops.createCustom(123, false, KeywordChange(text = "café", type = KeywordType.TOPIC))
            // 기존 utf8mb4_unicode_ci에서는 악센트 차이가 같은 unique 값일 수 있다.
            assertEquals("KEYWORD_DUPLICATED", assertFailsWith<LearningBusinessException> {
                ops.createCustom(123, false, KeywordChange(text = "cafe", type = KeywordType.TOPIC))
            }.code)
            assertEquals(1L, number(db, "SELECT COUNT(*) FROM language_learning_custom_keyword"))
        } }
    }
    @Test fun `실제 FK는 부모 삭제와 Core 사용자 참조를 허용하지 않는다`() {
        db { db, _, ops -> runBlocking {
            val parent = ops.createSystem(900, KeywordChange(text = "IT", type = KeywordType.TOPIC))
            ops.createCustom(123, false, KeywordChange(text = "deploy", type = KeywordType.VOCABULARY, parentKeywordId = parent.id))
            assertFailsWith<java.sql.SQLException> { sql(db, "DELETE FROM language_learning_system_keyword WHERE id=${parent.id}") }
            assertEquals(0L, number(db, "SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND referenced_table_schema IS NOT NULL AND referenced_table_schema<>DATABASE()"))
        } }
    }
    @Test fun `비활성 learner를 다시 활성화하거나 키워드를 만들지 않는다`() {
        db { db, _, ops -> runBlocking {
            ops.list(123, false, "ko")
            sql(db, "UPDATE language_learning_learner SET status='SUSPENDED' WHERE user_id=123")
            assertFailsWith<LearnerUnavailableException> { ops.createCustom(123, false, KeywordChange(text = "IT", type = KeywordType.TOPIC)) }
            assertEquals(0L, number(db, "SELECT COUNT(*) FROM language_learning_custom_keyword"))
            assertEquals(1L, number(db, "SELECT COUNT(*) FROM language_learning_learner WHERE status='SUSPENDED'"))
        } }
    }
    @Test fun `실제 locale 매핑과 부모 예약 참조 보호를 확인한다`() {
        db { db, _, ops -> runBlocking {
            val parent = ops.createSystem(900, KeywordChange(text = "IT", type = KeywordType.TOPIC))
            val id = parent.id
            sql(db, "INSERT INTO language_learning_system_keyword_locale(system_keyword_id,locale,display_name) VALUES($id,'ko-KR','아이티'),($id,'ja-JP','IT分野')")
            val view = ops.list(123, false, "learning").systemKeywords.single()
            assertEquals("IT分野", view.displayName); assertEquals("아이티", view.secondaryDisplayName)
            ops.createCustom(123, true, KeywordChange(text = "deploy", type = KeywordType.VOCABULARY, parentKeywordId = id))
            assertFailsWith<LearningBusinessException> { ops.updateSystem(900, id, KeywordChange(active = false)) }
            assertEquals(1L, number(db, "SELECT COUNT(*) FROM language_learning_system_keyword WHERE active=1"))
        } }
    }
}
