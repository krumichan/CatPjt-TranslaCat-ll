package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.MigrationMode
import jp.co.translacat.languagelearning.support.CurrentSchema
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 새 기능은 이 테스트가 만든 로컬 DB에만 쓴다. V008의 기존 설정/성장은 보존한다. */
class WritingSchemaIntegrationTest {
    @Test
    fun `V008 데이터 보존 후 Writing 상태와 생성 증거를 추가한다`() = LocalScratchMysql.use { db ->
        val settings = db.settings()
        Flyway.configure()
            .dataSource(settings.jdbcUrl, settings.username, settings.password)
            .locations("classpath:db/migration")
            .defaultSchema(db.name)
            .schemas(db.name)
            .createSchemas(false)
            .cleanDisabled(true)
            .baselineOnMigrate(false)
            .target("008")
            .load()
            .migrate()

        db.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO language_learning_learner(user_id,created_at,updated_at) VALUES(101,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),(202,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                )
                statement.executeUpdate(
                    "UPDATE language_learning_admin_setting SET default_daily_sentence_count=7 WHERE id='DEFAULT'",
                )
            }
        }
        DatabaseFactory(settings).use { factory ->
            assertEquals(CurrentSchema.MIGRATION_COUNT - 8, factory.migrationReport.migrationsExecuted)
            assertEquals(CurrentSchema.VERSION, factory.migrationReport.schemaVersion.toInt())
        }
        db.connect().use { connection ->
            assertEquals(
                CurrentSchema.TABLE_COUNT,
                scalar(connection, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"),
            )
            assertEquals(
                7L,
                scalar(
                    connection,
                    "SELECT default_daily_sentence_count FROM language_learning_admin_setting WHERE id='DEFAULT'",
                ),
            )
            val tables = setOf(
                "language_learning_daily_set", "language_learning_daily_item",
                "language_learning_writing_answer", "language_learning_writing_evaluation",
                "language_learning_generation_fingerprint",
            )
            connection.metaData.getTables(db.name, null, "language_learning_%", arrayOf("TABLE")).use { rows ->
                val names = buildSet { while (rows.next()) add(rows.getString("TABLE_NAME")) }
                assertTrue(names.containsAll(tables))
            }
            val foreignKeys =
                connection.metaData.getImportedKeys(db.name, null, "language_learning_daily_item").use { rows ->
                    buildSet {
                        while (rows.next()) add(
                            rows.getString("PKTABLE_NAME") to rows.getString("PKCOLUMN_NAME"),
                        )
                    }
                }
            assertTrue("language_learning_daily_set" to "user_id" in foreignKeys)
            assertTrue("language_learning_daily_set" to "id" in foreignKeys)

            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO language_learning_daily_set(user_id,learning_date,writing_type,snapshot_id,sentence_count,status,snapshot_json,created_at,updated_at) VALUES(101,'2026-09-26','FREE','synthetic-snapshot-1',5,'GENERATING','{}',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                )
                statement.executeUpdate(
                    "INSERT INTO language_learning_daily_item(daily_set_id,user_id,item_order,difficulty,origin_text,keywords_json,focus_metrics_json,focus_reason,created_at,updated_at) VALUES(1,101,1,'REVIEW','synthetic','[]','[]','synthetic',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                )
                statement.executeUpdate(
                    "INSERT INTO language_learning_writing_answer(daily_item_id,user_id,attempt_date,answer_text,submitted_at,created_at,updated_at) VALUES(1,101,'2026-09-26','synthetic',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                )
                statement.executeUpdate(
                    "INSERT INTO language_learning_writing_evaluation(answer_id,user_id,evaluation_context,status,created_at,updated_at) VALUES(1,101,'DAILY','PENDING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                )
                statement.executeQuery(
                    "SELECT language_complexity_band,diversity_metadata_json FROM language_learning_daily_item WHERE id=1",
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(null, rows.getObject(1))
                    assertEquals(null, rows.getString(2))
                }
                assertSqlConstraint {
                    statement.executeUpdate(
                        "INSERT INTO language_learning_daily_set(user_id,learning_date,writing_type,snapshot_id,sentence_count,status,snapshot_json,created_at,updated_at) VALUES(101,'2026-09-26','FREE','synthetic-snapshot-2',5,'GENERATING','{}',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                    )
                }
                assertSqlConstraint {
                    statement.executeUpdate(
                        "INSERT INTO language_learning_daily_item(daily_set_id,user_id,item_order,difficulty,origin_text,keywords_json,focus_metrics_json,focus_reason,created_at,updated_at) VALUES(1,202,2,'NORMAL','synthetic','[]','[]','synthetic',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                    )
                }
                assertSqlConstraint {
                    statement.executeUpdate(
                        "INSERT INTO language_learning_writing_answer(daily_item_id,user_id,attempt_date,answer_text,submitted_at,created_at,updated_at) VALUES(1,202,'2026-09-27','synthetic',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                    )
                }
                assertSqlConstraint {
                    statement.executeUpdate(
                        "INSERT INTO language_learning_writing_evaluation(answer_id,user_id,evaluation_context,status,created_at,updated_at) VALUES(1,202,'DAILY','PENDING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                    )
                }
            }
        }
        DatabaseFactory(settings.copy(migrationMode = MigrationMode.VALIDATE)).use { factory ->
            assertEquals(0, factory.migrationReport.migrationsExecuted)
        }
        db.connect().use { connection ->
            assertEquals(
                1L,
                scalar(
                    connection,
                    "SELECT COUNT(*) FROM language_learning_writing_evaluation WHERE user_id=101 AND status='PENDING'",
                ),
            )
            assertEquals(
                7L,
                scalar(
                    connection,
                    "SELECT default_daily_sentence_count FROM language_learning_admin_setting WHERE id='DEFAULT'",
                ),
            )
        }
    }

    private fun assertSqlConstraint(block: () -> Unit) {
        val failure = assertFailsWith<SQLException> { block() }
        assertTrue(failure.sqlState.orEmpty().startsWith("23"), "Expected integrity SQLState, got ${failure.sqlState}")
    }

    private fun scalar(connection: Connection, sql: String): Long = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            check(rows.next())
            rows.getLong(1)
        }
    }
}
