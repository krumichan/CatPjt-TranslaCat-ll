package jp.co.translacat.languagelearning.features.leveltest.infrastructure.persistence

import jp.co.translacat.languagelearning.features.leveltest.application.LevelAnswerService
import jp.co.translacat.languagelearning.features.leveltest.application.LevelAudioService
import jp.co.translacat.languagelearning.features.leveltest.application.LevelSessionService
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelBaseline
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelPoolQuestion
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelTestAnswerMode
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelTestSessionStatus
import jp.co.translacat.languagelearning.features.leveltest.support.LevelFixtures
import jp.co.translacat.languagelearning.features.leveltest.support.MemoryAudioStore
import jp.co.translacat.languagelearning.features.leveltest.support.TestLevelAi
import jp.co.translacat.languagelearning.features.leveltest.support.TestLevelContext
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.CurrentSchema
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.flywaydb.core.Flyway
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

/** 일반 test에서 제외된다. 별도의 무작위 로컬 MySQL DB에서만 실행한다. */
class LevelTestPersistenceIntegrationTest {
    /** 직접 선택의 모든 초기 점수와 0회 평가를 실제 DB에서 확인하고 새 연결로 복원한다. */
    @Test
    fun `B1 B2 B3 직접 선택은 모든 점수를 초기화하며 실측 활동을 만들지 않는다`() {
        LocalScratchMysql.use { db ->
            // 준비 및 실행: 서로 다른 소유자에게 세 구간을 저장한다.
            DatabaseFactory(db.settings()).use { factory ->
                runBlocking {
                    val service = LevelSessionService(work(factory), TestLevelContext())
                    listOf("B1" to 20, "B2" to 47, "B3" to 62).forEachIndexed { index, (band, score) ->
                        val owner = index + 101L
                        val opened = if (band == "B3") {
                            service.start(owner, null, "started-$owner").also { previous ->
                                work(factory).write(owner) { records.saveItem(LevelFixtures.item(previous, 1)) }
                                assertTrue(service.status(owner).selfSelectionAvailable)
                            }
                        } else null
                        val session = service.selfSelect(owner, band, "choice-$owner")
                        assertEquals(session.id, service.selfSelect(owner, band, "choice-$owner").id)
                        assertEquals(score, service.status(owner).baseline?.score)
                        assertEquals(0, service.detail(owner, session.id).items.size)
                        if (opened != null) {
                            assertEquals(LevelTestSessionStatus.ABANDONED, service.session(owner, opened.id).status)
                            assertNull(service.status(owner).active)
                        }

                        // 검증: 실제 평가가 없으므로 점수만 채우고 횟수·신뢰도는 늘리지 않는다.
                        db.connect().use { connection ->
                            connection.prepareStatement("SELECT * FROM language_learning_profile WHERE user_id=?").use { query ->
                                query.setLong(1, owner)
                                query.executeQuery().use { result ->
                                    assertTrue(result.next())
                                    listOf("base_level_score", "meaning_score", "grammar_score", "vocabulary_score",
                                        "naturalness_score", "expression_score", "review_performance",
                                        "normal_performance", "challenge_performance").forEach { column ->
                                        assertEquals(score.toDouble(), result.getDouble(column), column)
                                        assertFalse(result.wasNull(), column)
                                    }
                                    assertEquals(0, result.getInt("evaluation_count"))
                                    assertEquals(0.0, result.getDouble("confidence"))
                                    assertEquals(session.uid, result.getString("baseline_completion_id"))
                                    assertFalse(result.next())
                                }
                            }
                        }
                    }
                }
            }

            // 검증: 재시작 후에도 기준점이 남고 가짜 학습 활동이나 평가가 생기지 않는다.
            DatabaseFactory(db.settings()).use { reopened ->
                runBlocking {
                    val service = LevelSessionService(work(reopened), TestLevelContext())
                    assertEquals(47, service.status(102).baseline?.score)
                    assertTrue(service.status(102).initialCompleted)
                    assertEquals(1, service.history(102).size)
                    assertEquals(62, service.status(103).baseline?.score)
                    assertFalse(service.status(103).selfSelectionAvailable)
                }
            }
            assertEquals(4L, scalar(db, "SELECT COUNT(*) FROM language_learning_level_test_session"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM language_learning_level_test_item"))
            assertEquals(3L, scalar(db, "SELECT COUNT(*) FROM language_learning_profile"))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_level_test_evaluation"))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_activity"))
        }
    }

    private val clock = Clock.fixed(Instant.parse("2026-09-25T01:00:00Z"), ZoneOffset.UTC)
    private fun work(f: DatabaseFactory) = ExposedLevelTestUnitOfWork(JdbcTransactionRunner(f.database, 4), clock)

    @Test
    fun `V006에서 V007로 올려도 기존 설정과 Journal을 변경하지 않는다`() {
        LocalScratchMysql.use { db ->
            val s = db.settings()
            Flyway.configure()
                .dataSource(s.jdbcUrl, s.username, s.password)
                .locations("classpath:db/migration")
                .schemas(db.name)
                .defaultSchema(db.name)
                .createSchemas(false)
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .target("006")
                .load()
                .migrate()
            db.connect()
                .use {
                    it.createStatement()
                        .use { q ->
                            q.executeUpdate(
                                "UPDATE language_learning_admin_setting SET daily_keyword_max_count=6 WHERE id='DEFAULT'",
                            )
                        }
                }
            DatabaseFactory(s).use { f ->
                assertEquals(CurrentSchema.MIGRATION_COUNT - 6, f.migrationReport.migrationsExecuted); assertEquals(
                CurrentSchema.VERSION, f.migrationReport.schemaVersion.toInt(),
            )
                assertEquals(
                    CurrentSchema.TABLE_COUNT,
                    scalar(db, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"),
                )
                assertEquals(
                    6L,
                    scalar(
                        db, "SELECT daily_keyword_max_count FROM language_learning_admin_setting WHERE id='DEFAULT'",
                    ),
                )
                assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_result_event"))
            }
            DatabaseFactory(s).use { assertEquals(0, it.migrationReport.migrationsExecuted) }
        }
    }

    @Test
    fun `두 connection pool에서 동시에 시작해도 활성 세션은 한 개다`() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { a ->
                DatabaseFactory(db.settings()).use { b ->
                    runBlocking {
                        val left = LevelSessionService(work(a), TestLevelContext());
                        val right = LevelSessionService(work(b), TestLevelContext())
                        val sessions = withTimeout(30_000) {
                            (1..24).map { n ->
                                async {
                                    (if (n % 2 == 0) left else right).start(
                                        123, null, "start-$n",
                                    )
                                }
                            }.awaitAll()
                        }
                        assertEquals(1, sessions.map { it.id }.distinct().size)
                        assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM language_learning_level_test_session"))
                    }
                }
            }
        }
    }

    @Test
    fun `20문항과 최종 기준점을 실제 MySQL에 저장하고 재조회한다`() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { f ->
                runBlocking {
                    val w = work(f);
                    val ctx = TestLevelContext();
                    val sessions = LevelSessionService(w, ctx);
                    val s = sessions.start(123, null, "twenty")
                    val audio = LevelAudioService(w, MemoryAudioStore(), "http://localhost");
                    val answers = LevelAnswerService(w, ctx, TestLevelAi(), audio)
                    repeat(20) { n ->
                        val current = sessions.session(123, s.id)
                        val item = w.write(123) { records.saveItem(LevelFixtures.item(current, n + 1, nowUtc)) }
                        when (item.data.answerMode) {
                            LevelTestAnswerMode.CHOICE -> answers.submitText(
                                123, s.id, item.id, "q$n", if (n == 5) null else "A",
                                if (n == 5) listOf("A", "B", "C", "D") else null, null,
                            )

                            LevelTestAnswerMode.TEXT -> answers.submitText(
                                123, s.id, item.id, "q$n", null, null, "回答です。",
                            )

                            LevelTestAnswerMode.AUDIO -> answers.submitAudio(
                                123, s.id, item.id, "q$n", 1000, LevelFixtures.wav, "audio/wav",
                            )
                        }
                    }
                    assertEquals(90, sessions.baseline(123)?.score); assertEquals(
                    LevelTestSessionStatus.COMPLETED, sessions.session(123, s.id).status,
                )
                    assertEquals(20, sessions.detail(123, s.id).items.size)
                    assertEquals(20L, scalar(db, "SELECT COUNT(*) FROM language_learning_level_test_evaluation"))
                    assertEquals(
                        1L, scalar(db, "SELECT COUNT(*) FROM language_learning_profile WHERE base_level_score=90"),
                    )
                    assertEquals(
                        1L, scalar(db, "SELECT COUNT(*) FROM language_learning_activity WHERE source='LEVEL_TEST'"),
                    )
                    val again = LevelSessionService(work(f), ctx)
                    assertEquals(s.uid, again.baseline(123)?.completionId)
                }
            }
        }
    }

    @Test
    fun `쓰기 트랜잭션 실패는 세션과 기준점을 함께 롤백한다`() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { f ->
                runBlocking {
                    val w = work(f)
                    assertFailsWith<IllegalStateException> {
                        w.write(123) {
                            val s = records.saveSession(LevelFixtures.session(nowUtc))
                            records.saveBaseline(
                                LevelBaseline(
                                    123, s.id, s.uid, s.sessionType, 80, "UPPER_INTERMEDIATE", nowUtc.toLocalDate(),
                                    nowUtc, nowUtc,
                                ),
                            )
                            error("커밋 전 실패")
                        }
                    }
                    assertEquals(
                        0L, scalar(db, "SELECT COUNT(*) FROM language_learning_level_test_session"),
                    ); assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_level_test_baseline"))
                    assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_profile"))
                    assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_activity"))
                }
            }
        }
    }

    @Test
    fun `풀 upsert는 기존 문항 payload와 격리 상태를 덮어쓰지 않는다`() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { f ->
                runBlocking {
                    val w = work(f);
                    val session = LevelSessionService(w, TestLevelContext()).start(123, null, "pool")
                    val first = w.write(123) {
                        records.savePool(
                            LevelPoolQuestion(
                                originLanguage = "ko", learningLanguage = "ja",
                                data = LevelFixtures.question(session, 1), createdAt = nowUtc,
                            ),
                        )
                    }
                    w.write(123) { records.savePool(first.copy(active = false, quarantineReason = "INVALID")) }
                    val second = w.write(123) {
                        records.savePool(
                            first.copy(id = 0, data = first.data.copy(promptText = "別の文"), active = true),
                        )
                    }
                    assertEquals(first.id, second.id); assertFalse(second.active); assertEquals(
                    first.data.promptText, second.data.promptText,
                )
                    assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM language_learning_level_test_question_pool"))
                }
            }
        }
    }

    @Test
    fun `maintenance lease는 다른 token으로 해제할 수 없다`() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { f ->
                runBlocking {
                    val w = work(f)
                    w.write(null) {
                        assertTrue(records.claimMaintenance("token-a", nowUtc, nowUtc.plusSeconds(30)))
                        assertFalse(records.claimMaintenance("token-b", nowUtc, nowUtc.plusSeconds(30)))
                        assertFalse(records.releaseMaintenance("token-b")); assertTrue(
                        records.ownsMaintenance("token-a", nowUtc),
                    )
                        assertTrue(records.releaseMaintenance("token-a"))
                    }
                }
            }
        }
    }

    private fun scalar(db: LocalScratchMysql, sql: String): Long = db.connect()
        .use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { r -> check(r.next()); r.getLong(1) } } }
}
