package jp.co.translacat.languagelearning.features.growth.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.application.ApplyLevelBaseline
import jp.co.translacat.languagelearning.features.growth.application.GrowthProjector
import jp.co.translacat.languagelearning.features.growth.application.QueryGrowth
import jp.co.translacat.languagelearning.features.growth.domain.model.EvidenceFact
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthActivity
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthChange
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthMetric
import jp.co.translacat.languagelearning.features.growth.domain.repository.GrowthRepository
import jp.co.translacat.languagelearning.features.learner.domain.exception.LearnerUnavailableException
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.CurrentSchema
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.*
import org.flywaydb.core.Flyway
import java.time.*
import kotlin.test.*

/** 실서버/AI 없이, 이 테스트가 생성한 무작위 로컬 DB만 사용한다. */
class GrowthPersistenceIntegrationTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-25T10:01:00Z"), ZoneOffset.UTC)
    private fun work(factory: DatabaseFactory) =
        ExposedGrowthUnitOfWork(JdbcTransactionRunner(factory.database, 4), clock)

    private fun number(db: LocalScratchMysql, sql: String): Long = db.connect()
        .use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { r -> check(r.next()); r.getLong(1) } } }

    private fun sql(db: LocalScratchMysql, statement: String) {
        db.connect().use { it.createStatement().use { s -> s.executeUpdate(statement) } }
    }

    private val now = LocalDateTime.parse("2026-09-25T10:00:00")
    private val day = now.toLocalDate()
    private fun activity(ref: String, user: Long = 123) = GrowthActivity(
        userId = user, source = "SPEAKING", referenceId = ref, learningDate = day, title = "합성 연습",
        durationSeconds = 60, status = "COMPLETED", startedAt = now, completedAt = now.plusMinutes(1),
        createdAt = now, updatedAt = now,
    )

    @Test
    fun `V007의 완료 기준점과 설정을 보존하고 V008에서 로컬 성장 기준점을 만든다`() {
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
                .target("007")
                .load()
                .migrate()
            sql(
                db,
                "INSERT INTO language_learning_learner(user_id,status,identity_version,created_at,updated_at) VALUES(123,'ACTIVE',17,'2026-09-25 10:00:00','2026-09-25 10:00:00')",
            )
            sql(db, "UPDATE language_learning_admin_setting SET daily_keyword_max_count=6 WHERE id='DEFAULT'")
            val id = "aa9287bb-3f91-4ac4-ab79-ac43b1e0ea88"
            sql(
                db,
                "INSERT INTO language_learning_level_test_session(id,session_uid,user_id,session_type,status,origin_language,learning_language,timezone,current_question_number,current_complexity_band,base_level_score,proficiency_band,domain_scores_json,started_at,last_activity_at,completed_at,completed_date,idempotency_key) VALUES(1,'$id',123,'INITIAL','COMPLETED','ko','ja','Asia/Tokyo',20,4,83,'UPPER_INTERMEDIATE','{}','2026-09-25 09:00:00','2026-09-25 10:00:00','2026-09-25 10:00:00','2026-09-25','seed-1')",
            )
            sql(
                db,
                "INSERT INTO language_learning_level_test_baseline(user_id,session_id,completion_id,session_type,base_level_score,proficiency_band,completed_date,started_at,completed_at) VALUES(123,1,'$id','INITIAL',83,'UPPER_INTERMEDIATE','2026-09-25','2026-09-25 09:00:00','2026-09-25 10:00:00')",
            )
            DatabaseFactory(s).use { f ->
                assertEquals(CurrentSchema.MIGRATION_COUNT - 7, f.migrationReport.migrationsExecuted); assertEquals(
                CurrentSchema.VERSION, f.migrationReport.schemaVersion.toInt(),
            )
                assertEquals(
                    CurrentSchema.TABLE_COUNT,
                    number(db, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"),
                )
                assertEquals(
                    17, number(db, "SELECT identity_version FROM language_learning_learner WHERE user_id=123").toInt(),
                )
                assertEquals(
                    6,
                    number(
                        db, "SELECT daily_keyword_max_count FROM language_learning_admin_setting WHERE id='DEFAULT'",
                    ).toInt(),
                )
                runBlocking {
                    work(f).read {
                        val p = checkNotNull(records.profile(123)); assertEquals(83.0, p.baseLevelScore); assertEquals(
                        id, p.baselineCompletionId,
                    ); assertEquals("CALIBRATING", p.state)
                        assertEquals(
                            3600, records.activity(123, "LEVEL_TEST", "LL_LEVEL_TEST:$id")!!.durationSeconds.toInt(),
                        )
                    }
                    work(f).write(123) {
                        ApplyLevelBaseline(records).execute(
                            123, id, 83.0, LocalDate.parse("2026-09-25"), LocalDateTime.parse("2026-09-25T09:00:00"),
                            LocalDateTime.parse("2026-09-25T10:00:00"),
                        )
                    }
                }
                assertEquals(1L, number(db, "SELECT COUNT(*) FROM language_learning_activity")); assertEquals(
                0L, number(db, "SELECT COUNT(*) FROM language_learning_growth_receipt"),
            )
            }
            DatabaseFactory(s).use { assertEquals(0, it.migrationReport.migrationsExecuted) }
        }
    }

    @Test
    fun `옛 수신 테이블이 없어도 현재 점수 signal mastery와 Speaking 근거를 저장한다`() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { factory ->
                runBlocking {
                    // 준비: 이 검사가 만든 임시 DB에서만 옛 전송 테이블의 부재를 재현한다.
                    for (table in listOf(
                        "growth_operation", "growth_receipt", "growth_stream", "result_event", "result_stream",
                        "settings_selection_delivery",
                    )) {
                        sql(db, "DROP TABLE language_learning_$table")
                    }
                    val w = work(factory)
                    val metric = GrowthMetric("FLUENCY", "EVALUATED", 75.0, 0.9, null)

                    // 실행: 점수와 공식 활동은 현재 업무 트랜잭션에서 직접 반영한다.
                    w.write(123) {
                        val projector = GrowthProjector(records)
                        projector.apply(123, GrowthChange.KeywordsSelected(day, listOf("travel")), now)
                        projector.apply(
                            123,
                            GrowthChange.WritingScored(
                                day, "NORMAL", List(5) { 80.0 },
                                mapOf("STRENGTH" to listOf("clear")), listOf("travel"),
                            ),
                            now,
                        )
                        projector.apply(
                            123,
                            GrowthChange.SpeakingScored(
                                activity("official").copy(status = "EVALUATED"), listOf(metric), true, 0.8,
                                listOf(EvidenceFact("FLUENCY", "hesitation", "WEAKNESS", 0.9, "연결 연습")),
                            ),
                            now,
                        )
                    }
                    val snapshot = QueryGrowth(w).snapshot(123, null)
                    val page = QueryGrowth(w).activities(123, null, day, day, 0)

                    // 검증
                    assertEquals(1, snapshot.profile!!.evaluationCount)
                    assertEquals(80.0, snapshot.profile.meaningScore)
                    assertEquals(1, snapshot.masteries.single().selectedCount)
                    assertEquals(80.0, snapshot.masteries.single().score)
                    assertEquals(1, snapshot.signals.getValue("STRENGTH").single().occurrenceCount)
                    assertEquals(listOf(metric), page.activities.single().second)
                    assertEquals("2", page.projectionRevision)
                    assertEquals(1L, number(db, "SELECT COUNT(*) FROM language_learning_profile_evidence"))
                }
            }
        }
    }

    @Test
    fun `같은 시각의 활동 수정과 지표 교체도 revision을 바꾸고 실패하면 모두 롤백한다`() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { factory ->
                runBlocking {
                    // 준비
                    val w = work(factory)
                    val original = w.write(123) { records.saveActivity(activity("revision")) }
                    assertEquals(1L, w.read { records.activityRevision(123) })

                    // 실행: updatedAt이 같아도 자료 변경은 서로 다른 버전이다.
                    w.write(123) { records.saveActivity(original.copy(title = "수정 연습")) }
                    w.write(123) {
                        records.replaceMetrics(
                            original.id, listOf(GrowthMetric("FLUENCY", "EVALUATED", 75.0, 0.9, null)),
                        )
                    }
                    assertEquals(3L, w.read { records.activityRevision(123) })
                    assertFailsWith<IllegalArgumentException> {
                        w.write(123) {
                            records.saveActivity(original.copy(title = "롤백할 제목"))
                            records.replaceMetrics(original.id, emptyList())
                            GrowthProjector(records).apply(
                                123, GrowthChange.SignalsTouched("INVALID", listOf("x")), now,
                            )
                        }
                    }

                    // 검증: 데이터와 버전은 같은 커밋 단위다.
                    w.read {
                        assertEquals(3L, records.activityRevision(123))
                        assertEquals("수정 연습", records.activity(123, "SPEAKING", "revision")!!.title)
                        assertEquals(1, records.metrics(original.id).size)
                        assertEquals(0L, records.activityRevision(456))
                    }
                }
            }
        }
    }

    @Test
    fun `여러 pool의 현재 활동 쓰기는 revision 증분을 유실하지 않는다`() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { first ->
                DatabaseFactory(db.settings()).use { second ->
                    runBlocking {
                        // 준비
                        val left = work(first)
                        val right = work(second)

                        // 실행
                        withTimeout(30_000) {
                            (1..16).map { n ->
                                async {
                                    (if (n % 2 == 0) left else right).write(
                                        123,
                                    ) { records.saveActivity(activity(n.toString())) }
                                }
                            }.awaitAll()
                        }

                        // 검증
                        assertEquals(16L, left.read { records.activityRevision(123) })
                        assertEquals(16L, number(db, "SELECT COUNT(*) FROM language_learning_activity"))
                    }
                }
            }
        }
    }

    @Test
    fun `페이지의 사용자 날짜 소스 커서와 변경 revision을 유지한다`() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { factory ->
                runBlocking {
                    // 준비
                    val w = work(factory)
                    w.write(123) { repeat(27) { records.saveActivity(activity(it.toString())) } }
                    w.write(456) { records.saveActivity(activity("other", 456)) }
                    val query = QueryGrowth(w)

                    // 실행 및 검증: 같은 읽기 버전에서 25개와 나머지 2개를 읽는다.
                    val first = query.activities(123, "SPEAKING", day, day, 0)
                    val second = query.activities(123, "SPEAKING", day, day, first.nextAfterId!!)
                    assertEquals(25, first.activities.size)
                    assertEquals(2, second.activities.size)
                    assertNull(second.nextAfterId)
                    assertEquals(first.projectionRevision, second.projectionRevision)
                    assertTrue(query.activities(123, "READING", day, day, 0).activities.isEmpty())
                    assertTrue(query.activities(123, null, day.plusDays(1), day.plusDays(1), 0).activities.isEmpty())

                    // 실행 및 검증: 첫 페이지를 읽은 뒤 현재 결과가 바뀌면 다음 페이지의 버전도 달라진다.
                    w.write(123) { records.replaceMetrics(first.activities.first().first.id, emptyList()) }
                    assertNotEquals(
                        first.projectionRevision,
                        query.activities(123, "SPEAKING", day, day, first.nextAfterId!!).projectionRevision,
                    )
                }
            }
        }
    }

    @Test
    fun `읽기 트랜잭션의 revision과 활동은 중간 커밋에도 같은 snapshot을 유지한다`() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { first ->
                DatabaseFactory(db.settings()).use { second ->
                    runBlocking {
                        // 준비
                        val left = work(first)
                        val right = work(second)
                        left.write(123) { records.saveActivity(activity("before")) }
                        val readStarted = java.util.concurrent.CountDownLatch(1)
                        val writeDone = java.util.concurrent.CountDownLatch(1)

                        // 실행: revision을 읽은 뒤 다른 pool이 활동을 추가한다.
                        val reader = async(Dispatchers.IO) {
                            left.read {
                                val revision = records.activityRevision(123)
                                readStarted.countDown()
                                check(writeDone.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                revision to records.activities(123, null, day, day, 0, 25).size
                            }
                        }
                        withContext(Dispatchers.IO) {
                            check(
                                readStarted.await(10, java.util.concurrent.TimeUnit.SECONDS),
                            )
                        }
                        try {
                            right.write(123) { records.saveActivity(activity("after")) }
                        } finally {
                            writeDone.countDown()
                        }

                        // 검증: 진행 중 snapshot과 그 다음 조회가 각각 일관된 버전을 반환한다.
                        assertEquals(1L to 1, reader.await())
                        assertEquals("2", QueryGrowth(left).activities(123, null, day, day, 0).projectionRevision)
                    }
                }
            }
        }
    }

    @Test
    fun `빈 사용자 조회는 자료를 만들지 않고 비활성 학습자는 조회와 쓰기를 차단한다`() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { factory ->
                runBlocking {
                    // 준비 및 실행
                    val w = work(factory)
                    assertNull(QueryGrowth(w).snapshot(123, null).profile)
                    assertEquals("0", QueryGrowth(w).activities(123, null, day, day, 0).projectionRevision)

                    // 검증
                    assertEquals(0L, number(db, "SELECT COUNT(*) FROM language_learning_learner"))
                    assertEquals(0L, number(db, "SELECT COUNT(*) FROM language_learning_activity_revision"))
                    sql(
                        db,
                        "INSERT INTO language_learning_learner VALUES(123,'DISABLED',4,'2026-09-25 10:00:00','2026-09-25 10:00:00')",
                    )
                    assertFailsWith<LearnerUnavailableException> {
                        w.write(123) {
                            records.saveActivity(
                                activity("blocked"),
                            )
                        }
                    }
                    assertFailsWith<LearnerUnavailableException> { QueryGrowth(w).snapshot(123, null) }
                    assertEquals(
                        4L, number(db, "SELECT identity_version FROM language_learning_learner WHERE user_id=123"),
                    )
                }
            }
        }
    }

    @Test
    fun `repository는 트랜잭션 바깥으로 탈출할 수 없다`() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { factory ->
                // 준비 및 실행
                var escaped: GrowthRepository? = null
                runBlocking { work(factory).read { escaped = records } }

                // 검증
                assertFailsWith<IllegalStateException> { escaped!!.profile(123) }
                assertFailsWith<IllegalStateException> { escaped!!.activityRevision(123) }
            }
        }
    }
}
