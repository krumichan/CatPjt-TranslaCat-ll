package jp.co.translacat.languagelearning.features.growth.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.application.ApplyLevelBaseline
import jp.co.translacat.languagelearning.features.growth.application.GrowthProjector
import jp.co.translacat.languagelearning.features.growth.domain.model.EvidenceFact
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthActivity
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthChange
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthEvidence
import jp.co.translacat.languagelearning.features.growth.domain.policy.GrowthLanguageScope
import jp.co.translacat.languagelearning.features.settings.domain.model.NewUserSettings
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.repository.ExposedUserSettingsRepository
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.repository.ExposedWritingSetRepository
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 이 테스트가 생성한 무작위 로컬 DB에서만 새 migration과 실제 저장 격리를 검증한다. */
class GrowthPersistenceIntegrationTestLanguageScope {
    private val now = LocalDateTime.parse("2026-10-16T01:00:00")
    private val date = now.toLocalDate()

    private fun sql(db: LocalScratchMysql, statement: String) {
        db.connect().use { c -> c.createStatement().use { it.executeUpdate(statement) } }
    }

    @Test
    fun `일본어와 영어 프로필 숙련도 신호 근거를 각각 보존하고 늦은 평가도 원래 언어에 반영한다`() {
        // 준비
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { factory ->
                val work = ExposedGrowthUnitOfWork(JdbcTransactionRunner(factory.database, 4))
                runBlocking {
                    work.write(123) {
                        val settings = ExposedUserSettingsRepository { }
                        val created = settings.create(NewUserSettings(123, 5, 5, 5, now))
                        settings.save(created.copy(originLanguage = "ko", learningLanguage = "ja"))
                        val japanese = records.scoped("ko", "ja")
                        ApplyLevelBaseline(japanese).execute(123, "ja-baseline", 85.0, date, now, now)
                        GrowthProjector(japanese).apply(
                            123,
                            GrowthChange.WritingScored(date, "NORMAL", List(5) { 90.0 },
                                mapOf("STRENGTH" to listOf("shared-key")), listOf("travel")),
                            now,
                        )
                        japanese.saveEvidence(GrowthEvidence(
                            123, "SPEAKING", "FLUENCY", "shared-key", "WEAKNESS", 1,
                            1.0, 0.9, "Japanese practice", now, now, now,
                        ))
                    }

                    // 실행: 영어 활성화 후 같은 canonical key와 pattern key에 다른 점수를 저장한다.
                    work.write(123) {
                        val settings = ExposedUserSettingsRepository { }
                        settings.save(checkNotNull(settings.findForUser(123)).copy(learningLanguage = "en"))
                        assertNull(records.profile(123))
                        assertTrue(records.masteries(123, null, 10).isEmpty())
                        assertTrue(records.signals(123, "STRENGTH", 10).isEmpty())
                        assertTrue(records.evidenceList(123, null, 10).isEmpty())
                        ApplyLevelBaseline(records).execute(123, "en-baseline", 30.0, date, now, now)
                        GrowthProjector(records).apply(
                            123,
                            GrowthChange.WritingScored(date, "NORMAL", List(5) { 40.0 },
                                mapOf("STRENGTH" to listOf("shared-key")), listOf("travel")),
                            now,
                        )
                        val activity = GrowthActivity(
                            userId = 123, source = "SPEAKING", referenceId = "english-speaking",
                            learningDate = date, title = "English practice", durationSeconds = 0, status = "EVALUATED",
                            startedAt = now, completedAt = now, createdAt = now, updatedAt = now,
                        )
                        GrowthProjector(records).apply(123, GrowthChange.SpeakingScored(
                            activity, emptyList(), true, 1.0,
                            listOf(EvidenceFact("FLUENCY", "shared-key", "WEAKNESS", 0.8, "English practice")),
                        ), now)
                        GrowthProjector(records.scoped("ko", "ja")).apply(
                            123, GrowthChange.WritingScored(date, "NORMAL", List(5) { 70.0 },
                                emptyMap(), listOf("travel")), now.plusSeconds(1),
                        )
                    }

                    // 검증: 새 요청과 재방문 모두 현재 언어 자료만 읽으며 일본어 자료도 남아 있다.
                    work.read {
                        val english = records.scoped("KO", "EN")
                        val japanese = records.scoped("ko", "ja")
                        assertEquals(30.0, records.profile(123)!!.baseLevelScore)
                        assertEquals(40.0, english.profile(123)!!.meaningScore)
                        assertEquals(40.0, english.mastery(123, "travel")!!.score)
                        assertEquals(1, english.signal(123, "STRENGTH", "shared-key")!!.occurrenceCount)
                        assertEquals("English practice", english.evidence(123, "SPEAKING", "shared-key", "WEAKNESS")!!.recommendedFocus)
                        assertEquals(85.0, japanese.profile(123)!!.baseLevelScore)
                        assertEquals(80.0, japanese.profile(123)!!.meaningScore)
                        assertEquals(80.0, japanese.mastery(123, "travel")!!.score)
                        assertEquals("Japanese practice", japanese.evidence(123, "SPEAKING", "shared-key", "WEAKNESS")!!.recommendedFocus)
                    }
                    work.write(123) {
                        val settings = ExposedUserSettingsRepository { }
                        settings.save(checkNotNull(settings.findForUser(123)).copy(learningLanguage = "ja"))
                    }
                    work.read { assertEquals(80.0, records.profile(123)!!.meaningScore) }
                }
            }
        }
    }

    @Test
    fun `V038 자료와 checksum을 보존하고 원본이 증명하는 프로필만 새 언어 범위에 연결한다`() {
        // 준비: 이전 schema에 실제 완료 원본과 귀속 불명 숙련도를 넣는다.
        LocalScratchMysql.use { db ->
            val settings = db.settings()
            Flyway.configure().dataSource(settings.jdbcUrl, settings.username, settings.password)
                .locations("classpath:db/migration").schemas(db.name).defaultSchema(db.name)
                .createSchemas(false).cleanDisabled(true).target("038").load().migrate()
            sql(db, "INSERT INTO language_learning_learner VALUES(123,'ACTIVE',1,'2026-10-16 01:00:00','2026-10-16 01:00:00')")
            val completion = "d0e049ee-5a52-41e3-a4eb-988f821cc2e1"
            sql(db, """
                INSERT INTO language_learning_level_test_session
                (session_uid,user_id,session_type,status,origin_language,learning_language,timezone,
                 current_question_number,current_complexity_band,base_level_score,proficiency_band,
                 domain_scores_json,started_at,last_activity_at,completed_at,completed_date,idempotency_key)
                VALUES('$completion',123,'INITIAL','COMPLETED','KO','JA','Asia/Tokyo',20,4,85,
                 'UPPER_INTERMEDIATE','{}','2026-10-16 01:00:00','2026-10-16 01:00:00',
                 '2026-10-16 01:00:00','2026-10-16','legacy-test')
            """.trimIndent())
            sql(db, """
                INSERT INTO language_learning_profile
                (user_id,profile_version,state,base_level_score,calibration_started_date,meaning_score,
                 evaluation_count,confidence,trend,additional_signals_json,baseline_completion_id,
                 baseline_completed_at,created_at,updated_at,created_by,updated_by)
                VALUES(123,'PROFILE','CALIBRATING',85,'2026-10-16',99,0,0,'stable','{}','$completion',
                 '2026-10-16 01:00:00','2026-10-16 01:00:00','2026-10-16 01:00:00','fixture','fixture')
            """.trimIndent())
            sql(db, """
                INSERT INTO language_learning_keyword_mastery
                (user_id,canonical_key,score,evaluation_count,selected_count,created_at,updated_at,created_by,updated_by)
                VALUES(123,'legacy-travel',99,1,1,'2026-10-16 01:00:00','2026-10-16 01:00:00','fixture','fixture')
            """.trimIndent())
            sql(db, """
                INSERT INTO language_learning_daily_set
                (user_id,learning_date,writing_type,snapshot_id,sentence_count,status,snapshot_json,
                 regeneration_count,created_at,updated_at)
                VALUES(123,'2026-10-16','FREE','legacy-ja-writing',1,'GENERATING',
                 '{"originLanguage":"KO","learningLanguage":"JA"}',0,
                 '2026-10-16 01:00:00','2026-10-16 01:00:00')
            """.trimIndent())
            DatabaseFactory(settings).use { factory ->
                val work = ExposedGrowthUnitOfWork(JdbcTransactionRunner(factory.database, 4))
                // 실행: 새 migration은 별도 버전으로 적용하고 이전 checksum은 Flyway가 검증한다.
                assertEquals(1, factory.migrationReport.migrationsExecuted)
                runBlocking {
                    work.read {
                        assertEquals(85.0, records.scoped("ko", "ja").profile(123)!!.baseLevelScore)
                        assertNull(records.scoped("ko", "ja").profile(123)!!.meaningScore)
                        assertNull(records.scoped("ko", "en").profile(123))
                        assertTrue(records.scoped("ko", "ja").masteries(123, null, 10).isEmpty())
                        assertTrue(records.scoped("ko", "en").masteries(123, null, 10).isEmpty())
                        val sets = ExposedWritingSetRepository({ })
                        assertEquals("legacy-ja-writing", sets.scoped("ko", "ja").find(123, date, WritingType.FREE)!!.snapshotId)
                        assertNull(sets.scoped("ko", "en").find(123, date, WritingType.FREE))
                    }
                    work.write(123) {
                        ApplyLevelBaseline(records.scoped("ko", "ja")).execute(
                            123, completion, 85.0, date, now, now,
                        )
                    }
                }
            }
            // 검증: 재시작해도 migration을 반복하거나 기존 완료 원본을 삭제하지 않는다.
            DatabaseFactory(settings).use { assertEquals(0, it.migrationReport.migrationsExecuted) }
            db.connect().use { c -> c.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM language_learning_level_test_session WHERE session_uid='$completion'").use {
                    assertTrue(it.next()); assertEquals(1, it.getInt(1))
                }
                statement.executeQuery("SELECT COUNT(*) FROM language_learning_keyword_mastery WHERE canonical_key='legacy-travel' AND language_scope=''").use {
                    assertTrue(it.next()); assertEquals(1, it.getInt(1))
                }
                statement.executeQuery("SELECT meaning_score FROM language_learning_profile WHERE user_id=123 AND language_scope=''").use {
                    assertTrue(it.next()); assertEquals(99.0, it.getDouble(1))
                }
            } }
        }
    }

    @Test
    fun `언어 코드 정규화는 지역 tag와 언어쌍의 경계를 보존한다`() {
        // 준비 / 실행 / 검증
        assertEquals(GrowthLanguageScope.key("ko-KR", "en-US"), GrowthLanguageScope.key("KO_kr", "EN_us"))
        assertTrue(GrowthLanguageScope.key("ko", "en") != GrowthLanguageScope.key("en", "ko"))
        assertTrue(GrowthLanguageScope.key("ab2:cd", "ef") != GrowthLanguageScope.key("ab", "cd2:ef"))
    }
}
