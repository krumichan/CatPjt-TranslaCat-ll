package jp.co.translacat.languagelearning.features.resultjournal.infrastructure.persistence

import jp.co.translacat.languagelearning.features.resultjournal.application.AcceptLearningResult
import jp.co.translacat.languagelearning.features.resultjournal.domain.exception.ResultJournalConflict
import jp.co.translacat.languagelearning.features.resultjournal.domain.model.ReceiptOutcome
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.flywaydb.core.Flyway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import jp.co.translacat.languagelearning.support.ResultJournalFixtures as F

/** 실제 MySQL 검증. 기본 test에서는 실행하지 않으며 임시 DB만 사용한다. */
class ResultJournalIntegrationTest {
    private fun service(factory: DatabaseFactory) = AcceptLearningResult(
        ExposedResultJournalUnitOfWork(JdbcTransactionRunner(factory.database, 4)), F.SOURCE,
    )

    @Test
    fun `동일 전달을 반복해도 수신 행과 cursor는 한 번만 갱신된다`() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { factory ->
                runBlocking {
                    val accept = service(factory);
                    val event = F.event()
                    assertEquals(ReceiptOutcome.RECORDED, accept.execute(event).outcome)
                    repeat(5) { assertEquals(ReceiptOutcome.DUPLICATE, accept.execute(event).outcome) }
                    assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM language_learning_result_event"))
                    assertEquals(1L, scalar(db, "SELECT last_sequence FROM language_learning_result_stream"))
                    assertEquals(
                        0L, scalar(
                            db,
                            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='language_learning_profile'"
                        )
                    )
                }
            }
        }
    }

    @Test
    fun `두 connection pool에서 동시 중복 수신해도 한 번만 저장한다`() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { one ->
                DatabaseFactory(db.settings()).use { two ->
                    runBlocking {
                        val a = service(one);
                        val b = service(two);
                        val event = F.event()
                        // learner 최초 생성 경쟁 자체도 이 경로에서 검증한다.
                        val receipts = withTimeout(30_000) {
                            (1..16).map { i -> async { (if (i % 2 == 0) a else b).execute(event) } }.awaitAll()
                        }
                        assertEquals(1, receipts.count { it.outcome == ReceiptOutcome.RECORDED })
                        assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM language_learning_result_event"))
                    }
                }
            }
        }
    }

    @Test
    fun `순번 누락으로 실패한 트랜잭션은 learner와 stream도 남기지 않는다`() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { factory ->
                runBlocking {
                    val accept = service(factory)
                    assertFailsWith<ResultJournalConflict> { accept.execute(F.event(2)) }
                    assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_result_event"))
                    assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_result_stream"))
                    assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_learner"))
                    accept.execute(F.event(1)); accept.execute(F.event(2))
                    assertEquals(2L, scalar(db, "SELECT last_sequence FROM language_learning_result_stream"))
                }
            }
        }
    }

    @Test
    fun `이미 있는 eventId에 다른 본문을 적용하지 않는다`() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { factory ->
                runBlocking {
                    val accept = service(factory);
                    val event = F.event(); accept.execute(event)
                    assertFailsWith<ResultJournalConflict> {
                        accept.execute(
                            F.event(payload = F.PAYLOAD + " ").copy(eventId = event.eventId)
                        )
                    }
                    assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM language_learning_result_event"))
                }
            }
        }
    }

    @Test
    fun `cursor 갱신 SQL이 실패하면 수신 INSERT도 롤백한다`() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { factory ->
                runBlocking {
                    // 이 테스트만 소유한 임시 DB에 UPDATE 거부 트리거를 두어 원자성을 검증한다.
                    sql(
                        db,
                        "CREATE TRIGGER reject_result_cursor BEFORE UPDATE ON language_learning_result_stream FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test cursor rejected'"
                    )
                    val accept = service(factory)
                    kotlin.test.assertFails { accept.execute(F.event()) }
                    assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_result_event"))
                    assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_learner"))
                }
            }
        }
    }

    @Test
    fun `V005에서 V006으로 올려도 관리자 설정값을 보존한다`() {
        LocalScratchMysql.use { db ->
            val s = db.settings()
            Flyway.configure()
                .dataSource(s.jdbcUrl, s.username, s.password)
                .locations("classpath:db/migration")
                .defaultSchema(db.name)
                .schemas(db.name)
                .createSchemas(false)
                .cleanDisabled(true)
                .target("005")
                .load()
                .migrate()
            sql(db, "UPDATE language_learning_admin_setting SET daily_keyword_max_count=9 WHERE id='DEFAULT'")
            DatabaseFactory(s).use { f ->
                assertEquals(1, f.migrationReport.migrationsExecuted)
                assertEquals(6, f.migrationReport.schemaVersion.toInt())
                assertEquals(
                    9L,
                    scalar(db, "SELECT daily_keyword_max_count FROM language_learning_admin_setting WHERE id='DEFAULT'")
                )
                assertEquals(
                    14L, scalar(db, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")
                )
            }
            DatabaseFactory(s).use { assertEquals(0, it.migrationReport.migrationsExecuted) }
        }
    }

    private fun sql(db: LocalScratchMysql, sql: String) {
        db.connect().use { c -> c.createStatement().use { it.execute(sql) } }
    }

    private fun scalar(db: LocalScratchMysql, sql: String): Long {
        return db.connect().use { c ->
            c.createStatement().use { it.executeQuery(sql).use { r -> assertTrue(r.next()); r.getLong(1) } }
        }
    }
}
