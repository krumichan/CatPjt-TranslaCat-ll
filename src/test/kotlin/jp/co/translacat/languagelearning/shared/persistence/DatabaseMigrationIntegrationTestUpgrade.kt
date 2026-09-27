package jp.co.translacat.languagelearning.shared.persistence

import jp.co.translacat.languagelearning.support.CurrentSchema
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import org.flywaydb.core.Flyway
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseMigrationIntegrationTestUpgrade {
    private val preservedTables = listOf(
        "language_learning_learner", "language_learning_admin_setting", "language_learning_custom_keyword",
        "language_learning_level_test_session", "language_learning_level_test_baseline",
        "language_learning_profile", "language_learning_result_stream", "language_learning_result_event",
    )

    @Test
    fun `기존 V008 설정 키워드 레벨 성장 원장을 최신 버전과 재시작에서 그대로 보존한다`() = LocalScratchMysql.use { db ->
        // 준비: 일반 데이터를 복사하지 않고 원래 LL 스키마에 합성 완료 자료를 만든다.
        val settings = db.settings()
        val initial = Flyway.configure().dataSource(settings.jdbcUrl, settings.username, settings.password)
            .locations("classpath:db/migration").defaultSchema(db.name).schemas(db.name)
            .createSchemas(false).cleanDisabled(true).baselineOnMigrate(false).target("008").load().migrate()
        assertEquals(8, initial.migrationsExecuted)
        db.connect().use { connection -> seed(connection) }
        val before = db.connect().use { snapshot(it) }

        // 실행: 실제 MySQL에서 새 migration을 모두 적용한 뒤 VALIDATE 모드로 다시 연다.
        DatabaseFactory(settings).use { factory ->
            assertEquals(CurrentSchema.VERSION, factory.migrationReport.schemaVersion.toInt())
            assertEquals(CurrentSchema.MIGRATION_COUNT - 8, factory.migrationReport.migrationsExecuted)
        }
        DatabaseFactory(settings.copy(migrationMode = MigrationMode.VALIDATE)).use { factory ->
            assertEquals(0, factory.migrationReport.migrationsExecuted)
        }

        // 검증: 기존 행의 모든 컬럼·식별자·날짜·원문 JSON과 stream sequence가 동일하다.
        val after = db.connect().use { snapshot(it) }
        assertEquals(before, after)
    }

    private fun seed(connection: Connection) {
        val statements = listOf(
            """INSERT INTO language_learning_learner(user_id,created_at,updated_at)
                VALUES(781,'2026-09-20 01:00:00.123456','2026-09-20 01:00:00.123456')""",
            "UPDATE language_learning_admin_setting SET default_daily_sentence_count=7 WHERE id='DEFAULT'",
            """INSERT INTO language_learning_custom_keyword
                (user_id,text,normalized_text,keyword_type,canonical_key,active,available_from,pending_parent_changed,created_at,updated_at)
                VALUES(781,'Synthetic preserved','synthetic preserved','VOCABULARY','synthetic-preserved',1,
                '2026-09-20',0,'2026-09-20 01:00:00.123456','2026-09-20 01:00:00.123456')""",
            """INSERT INTO language_learning_level_test_session
                (id,session_uid,user_id,session_type,status,origin_language,learning_language,timezone,
                current_question_number,current_complexity_band,base_level_score,proficiency_band,domain_scores_json,
                started_at,last_activity_at,completed_at,completed_date,idempotency_key)
                VALUES(1,'00000000-0000-0000-0000-000000000781',781,'INITIAL','COMPLETED','ko','en','Asia/Seoul',
                20,3,60,'INTERMEDIATE','{}','2026-09-20 01:00:00','2026-09-20 01:10:00','2026-09-20 01:10:00',
                '2026-09-20','synthetic-preserved-level')""",
            """INSERT INTO language_learning_level_test_baseline
                (user_id,session_id,completion_id,session_type,base_level_score,proficiency_band,completed_date,started_at,completed_at)
                VALUES(781,1,'00000000-0000-0000-0000-000000000782','INITIAL',60,'INTERMEDIATE',
                '2026-09-20','2026-09-20 01:00:00','2026-09-20 01:10:00')""",
            """INSERT INTO language_learning_profile
                (user_id,profile_version,state,base_level_score,evaluation_count,confidence,trend,additional_signals_json,
                baseline_completion_id,baseline_completed_at,created_at,updated_at,created_by,updated_by)
                VALUES(781,'v1','ACTIVE',60,2,0.75,'STABLE','{"synthetic":true}',
                '00000000-0000-0000-0000-000000000782','2026-09-20 01:10:00',
                '2026-09-20 01:00:00','2026-09-20 01:10:00','synthetic','synthetic')""",
            """INSERT INTO language_learning_result_stream(source_instance_id,user_id,last_sequence)
                VALUES('00000000-0000-0000-0000-000000000783',781,1)""",
            """INSERT INTO language_learning_result_event
                (event_id,schema_version,source_instance_id,user_id,stream_sequence,kind,reference_id,occurred_at,
                payload_json,payload_sha256,aggregation_eligible,received_at)
                VALUES('00000000-0000-0000-0000-000000000784',1,'00000000-0000-0000-0000-000000000783',
                781,1,'SPEAKING_INSUFFICIENT','synthetic-781','2026-09-20T01:10:00Z',
                '{"synthetic":true}',SHA2('{"synthetic":true}',256),0,'2026-09-20 01:10:00.123456')""",
        )
        connection.createStatement().use { statement -> statements.forEach { statement.executeUpdate(it) } }
    }

    private fun snapshot(connection: Connection): Map<String, List<List<String?>>> =
        preservedTables.associateWith { table ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM $table ORDER BY 1").use { rows ->
                    buildList {
                        while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getString(it) })
                    }
                }
            }
        }
}
