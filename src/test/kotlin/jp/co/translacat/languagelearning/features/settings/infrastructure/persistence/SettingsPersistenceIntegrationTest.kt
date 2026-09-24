package jp.co.translacat.languagelearning.features.settings.infrastructure.persistence

import jp.co.translacat.languagelearning.features.learner.domain.exception.LearnerUnavailableException
import jp.co.translacat.languagelearning.features.settings.application.GetOrCreateUserSettings
import jp.co.translacat.languagelearning.features.settings.application.SettingsTransaction
import jp.co.translacat.languagelearning.features.settings.domain.exception.SettingsPolicyNotInitializedException
import jp.co.translacat.languagelearning.features.settings.domain.model.NewUserSettings
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.*
import java.sql.SQLException
import java.time.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** 일반 test에서는 제외한다. databaseIntegrationTest가 만든 loopback 임시 DB만 사용한다. */
class SettingsPersistenceIntegrationTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-24T03:30:00.123456789Z"), ZoneOffset.UTC)
    private val expectedTime = LocalDateTime.of(2026, 9, 24, 3, 30, 0, 123456000)

    @Test
    fun `처음 생성한 설정의 모든 컬럼과 감사 시각을 재조회할 수 있다`() = withDatabase { db, _, work ->
        runBlocking {
            val result = GetOrCreateUserSettings(work).execute(123)
            assertTrue(result.id > 0)
            assertEquals(123L, result.userId)
            assertEquals(5, result.dailySentenceCount)
            assertEquals(5, result.dailySpeakingGoalMinutes)
            assertEquals(5, result.dailyListeningGoalCount)
            assertEquals("Asia/Tokyo", result.timezone)
            assertEquals("marin", result.speakingVoiceId)
            assertEquals("NORMAL", result.speakingPlaybackSpeed)
            assertEquals("[\"DICTATION\"]", result.defaultListeningTaskTypesJson)
            assertNull(result.originLanguage)
            assertNull(result.learningLanguage)
            assertNull(result.pendingOriginLanguage)
            assertNull(result.pendingLearningLanguage)
            assertNull(result.pendingTimezone)
            assertNull(result.pendingDailySentenceCount)
            assertNull(result.pendingDailySpeakingGoalMinutes)
            assertNull(result.pendingDailyListeningGoalCount)
            assertNull(result.pendingEffectiveDate)
            assertFalse(result.configured)
            assertEquals(expectedTime, result.createdAt)
            assertEquals(expectedTime, result.updatedAt)
            assertEquals("123", result.createdBy)
            assertEquals("123", result.updatedBy)
        }
        assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM language_learning_learner"))
        assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM language_learning_user_setting"))
        assertEquals(7L, scalar(db, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"))
        assertEquals(4L, scalar(db, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1"))
    }

    @Test
    fun `재조회는 개인 설정과 learner의 identityVersion 및 시각을 덮어쓰지 않는다`() = withDatabase { db, factory, work ->
        runBlocking {
            val first = GetOrCreateUserSettings(work).execute(123)
            execute(db, "UPDATE language_learning_learner SET identity_version=17 WHERE user_id=123")
            val later = ExposedSettingsUnitOfWork(
                JdbcTransactionRunner(factory.database, 4),
                Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC),
            )
            val second = GetOrCreateUserSettings(later).execute(123)
            assertEquals(first, second)
            val learner = later.execute { learners.ensureAndLock(123, nowUtc) }
            assertEquals(17L, learner.identityVersion)
            assertEquals(expectedTime, learner.createdAt)
            assertEquals(expectedTime, learner.updatedAt)
        }
        assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM language_learning_user_setting"))
    }

    @Test
    fun `저장된 사용자 값과 pending은 초기화 조회에서 변경하지 않는다`() = withDatabase { db, _, work ->
        runBlocking {
            GetOrCreateUserSettings(work).execute(123)
            execute(
                db, """
                UPDATE language_learning_user_setting SET
                    origin_language='ko', learning_language='ja', timezone='Europe/Paris',
                    daily_sentence_count=40, daily_speaking_goal_minutes=30, daily_listening_goal_count=35,
                    default_listening_task_types='["SUMMARY"]', speaking_voice_id='cedar', speaking_playback_speed='SLOW',
                    pending_origin_language='ja', pending_learning_language='en', pending_timezone='UTC',
                    pending_daily_sentence_count=45, pending_daily_speaking_goal_minutes=40,
                    pending_daily_listening_goal_count=50, pending_effective_date='2000-01-01',
                    updated_by='LOCAL_ADMIN', updated_at='2026-09-25 01:02:03.123456'
                WHERE user_id=123
            """.trimIndent()
            )
            val value = GetOrCreateUserSettings(work).execute(123)
            assertTrue(value.configured)
            assertEquals("ko", value.originLanguage)
            assertEquals("ja", value.learningLanguage)
            assertEquals("Europe/Paris", value.timezone)
            assertEquals(40, value.dailySentenceCount)
            assertEquals(30, value.dailySpeakingGoalMinutes)
            assertEquals(35, value.dailyListeningGoalCount)
            assertEquals("[\"SUMMARY\"]", value.defaultListeningTaskTypesJson)
            assertEquals("cedar", value.speakingVoiceId)
            assertEquals("SLOW", value.speakingPlaybackSpeed)
            assertEquals("ja", value.pendingOriginLanguage)
            assertEquals("en", value.pendingLearningLanguage)
            assertEquals("UTC", value.pendingTimezone)
            assertEquals(45, value.pendingDailySentenceCount)
            assertEquals(40, value.pendingDailySpeakingGoalMinutes)
            assertEquals(50, value.pendingDailyListeningGoalCount)
            assertEquals(LocalDate.of(2000, 1, 1), value.pendingEffectiveDate)
            assertEquals("LOCAL_ADMIN", value.updatedBy)
            assertEquals(LocalDateTime.parse("2026-09-25T01:02:03.123456"), value.updatedAt)
            assertEquals(value, GetOrCreateUserSettings(work).execute(123))
        }
    }

    @Test
    fun `관리자 기본값 변경은 새로운 사용자에게만 적용한다`() = withDatabase { db, _, work ->
        runBlocking {
            val service = GetOrCreateUserSettings(work)
            val before = service.execute(1)
            execute(
                db,
                "UPDATE language_learning_admin_setting SET default_daily_sentence_count=7, default_daily_speaking_goal_minutes=8 WHERE id='DEFAULT'"
            )
            execute(db, "UPDATE language_learning_listening_policy_setting SET default_item_count=9 WHERE id='DEFAULT'")
            assertEquals(before, service.execute(1))
            val after = service.execute(2)
            assertEquals(7, after.dailySentenceCount)
            assertEquals(8, after.dailySpeakingGoalMinutes)
            assertEquals(9, after.dailyListeningGoalCount)
        }
    }

    @Test
    fun `관리자 정책이 없으면 새 learner까지 rollback하고 기존 설정은 유지한다`() = withDatabase { db, _, work ->
        runBlocking {
            val service = GetOrCreateUserSettings(work)
            val first = service.execute(1)
            execute(db, "DELETE FROM language_learning_admin_setting WHERE id='DEFAULT'")
            assertEquals(first, service.execute(1))
            assertFailsWith<SettingsPolicyNotInitializedException> { service.execute(2) }
        }
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_learner WHERE user_id=2"))
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_user_setting WHERE user_id=2"))
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_admin_setting"))
    }

    @Test
    fun `Listening 정책이 없어도 임의 기본값을 만들지 않고 rollback한다`() = withDatabase { db, _, work ->
        execute(db, "DELETE FROM language_learning_listening_policy_setting WHERE id='DEFAULT'")
        runBlocking {
            assertFailsWith<SettingsPolicyNotInitializedException> { GetOrCreateUserSettings(work).execute(123) }
        }
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_learner"))
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_user_setting"))
    }

    @Test
    fun `잘못된 정책을 읽으면 부분 learner와 설정을 남기지 않는다`() = withDatabase { db, _, work ->
        execute(db, "UPDATE language_learning_admin_setting SET default_daily_sentence_count=99 WHERE id='DEFAULT'")
        runBlocking {
            assertFailsWith<IllegalArgumentException> { GetOrCreateUserSettings(work).execute(123) }
        }
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_learner"))
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_user_setting"))
        assertEquals(99L, scalar(db, "SELECT default_daily_sentence_count FROM language_learning_admin_setting"))
    }

    @Test
    fun `비활성 또는 알 수 없는 learner를 재활성화하지 않는다`() = withDatabase { db, _, work ->
        runBlocking {
            for ((index, status) in listOf("SUSPENDED", "DELETION_PENDING", "DELETED", "UNKNOWN").withIndex()) {
                val id = index + 1L
                // 상태값은 이 테스트 내부의 고정 목록이다.
                execute(
                    db, """
                    INSERT INTO language_learning_learner (user_id, status, identity_version, created_at, updated_at)
                    VALUES ($id, '$status', 23, '2020-01-01', '2020-01-01')
                """.trimIndent()
                )
                assertFailsWith<LearnerUnavailableException> { GetOrCreateUserSettings(work).execute(id) }
                assertEquals(
                    23L, scalar(db, "SELECT identity_version FROM language_learning_learner WHERE user_id=$id")
                )
                assertEquals(status, stringScalar(db, "SELECT status FROM language_learning_learner WHERE user_id=$id"))
            }
        }
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_user_setting"))
    }

    @Test
    fun `동일 사용자의 동시 최초 요청은 독립 pool 두 개에서도 한 행만 생성한다`() = withDatabase { db, _, work ->
        DatabaseFactory(db.settings()).use { otherFactory ->
            val other = ExposedSettingsUnitOfWork(JdbcTransactionRunner(otherFactory.database, 4), clock)
            val services = listOf(GetOrCreateUserSettings(work), GetOrCreateUserSettings(other))
            runBlocking {
                withTimeout(30_000) {
                    val start = CompletableDeferred<Unit>()
                    val calls = (0 until 32).map { index ->
                        async { start.await(); services[index % 2].execute(123) }
                    }
                    start.complete(Unit)
                    val values = calls.awaitAll()
                    assertEquals(1, values.map { it.id }.distinct().size)
                    assertEquals(1, values.distinct().size)
                }
            }
        }
        assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM language_learning_learner"))
        assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM language_learning_user_setting"))
    }

    @Test
    fun `다른 사용자들의 동시 생성은 각각의 식별자와 설정으로 분리된다`() = withDatabase { db, _, work ->
        runBlocking {
            val service = GetOrCreateUserSettings(work)
            val results = withTimeout(30_000) { (1L..16L).map { id -> async { service.execute(id) } }.awaitAll() }
            assertEquals((1L..16L).toSet(), results.map { it.userId }.toSet())
            assertEquals(16, results.map { it.id }.distinct().size)
        }
        assertEquals(16L, scalar(db, "SELECT COUNT(*) FROM language_learning_user_setting"))
    }

    @Test
    fun `설정 INSERT 뒤 실패해도 learner와 설정을 함께 rollback한다`() = withDatabase { db, _, work ->
        runBlocking {
            assertFailsWith<IllegalStateException> {
                work.execute {
                    learners.ensureAndLock(123, nowUtc)
                    userSettings.create(NewUserSettings.fromPolicy(123, policies.loadInitialPolicy(), nowUtc))
                    error("rollback 검증")
                }
            }
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_learner"))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_user_setting"))
            assertNotNull(GetOrCreateUserSettings(work).execute(123))
        }
    }

    @Test
    fun `다른 DB가 나중에 등록되어도 지정한 DB만 사용한다`() = withDatabase { first, _, firstWork ->
        LocalScratchMysql.use { second ->
            DatabaseFactory(second.settings()).use { secondFactory ->
                execute(
                    second,
                    "UPDATE language_learning_admin_setting SET default_daily_sentence_count=9 WHERE id='DEFAULT'"
                )
                val secondWork = ExposedSettingsUnitOfWork(JdbcTransactionRunner(secondFactory.database, 4), clock)
                runBlocking {
                    assertEquals(5, GetOrCreateUserSettings(firstWork).execute(123).dailySentenceCount)
                    assertEquals(9, GetOrCreateUserSettings(secondWork).execute(123).dailySentenceCount)
                }
                assertEquals(1L, scalar(first, "SELECT COUNT(*) FROM language_learning_user_setting"))
                assertEquals(1L, scalar(second, "SELECT COUNT(*) FROM language_learning_user_setting"))
            }
        }
    }

    @Test
    fun `트랜잭션 종료 후 유출된 Repository 사용은 거부한다`() = withDatabase { _, _, work ->
        runBlocking {
            val escaped: SettingsTransaction = work.execute { this }
            assertFailsWith<IllegalStateException> { escaped.userSettings.findForUser(123) }
            assertFailsWith<IllegalStateException> { escaped.policies.loadInitialPolicy() }
            assertFailsWith<IllegalStateException> { escaped.learners.ensureAndLock(123, expectedTime) }
        }
    }

    @Test
    fun `중첩 UnitOfWork를 거부하고 바깥 변경도 rollback한다`() = withDatabase { db, _, work ->
        runBlocking {
            assertFailsWith<IllegalStateException> {
                work.execute {
                    learners.ensureAndLock(123, nowUtc)
                    // 비-suspend 블록 안에서 runBlocking으로 우회해도 중첩 실행은 허용하지 않는다.
                    runBlocking { GetOrCreateUserSettings(work).execute(456) }
                }
            }
        }
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_learner"))
    }

    @Test
    fun `분류된 잠금 실패만 새 트랜잭션에서 제한적으로 재시도한다`() = withDatabase { db, factory, _ ->
        val work = ExposedSettingsUnitOfWork(JdbcTransactionRunner(factory.database, 4), clock)
        var attempts = 0
        runBlocking {
            work.execute {
                learners.ensureAndLock(123, nowUtc)
                attempts += 1
                // 실제 데드락 발생 시험이 아니라 retry 분류와 rollback 경계 시험이다.
                if (attempts == 1) throw SQLException("검증용 데드락", "40001", 1213)
            }
        }
        assertEquals(2, attempts)
        assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM language_learning_learner"))
    }

    @Test
    fun `잠금 재시도 상한은 세 번이고 통신 실패는 재시도하지 않는다`() = withDatabase { db, factory, _ ->
        val work = ExposedSettingsUnitOfWork(JdbcTransactionRunner(factory.database, 4), clock)
        runBlocking {
            var lockAttempts = 0
            assertFailsWith<SQLException> {
                work.execute {
                    learners.ensureAndLock(123, nowUtc)
                    lockAttempts += 1
                    throw SQLException("검증용 잠금 실패", "HY000", 1205)
                }
            }
            assertEquals(3, lockAttempts)
            var networkAttempts = 0
            assertFailsWith<SQLException> {
                work.execute {
                    learners.ensureAndLock(123, nowUtc)
                    networkAttempts += 1
                    throw SQLException("검증용 통신 실패", "08S01", 0)
                }
            }
            assertEquals(1, networkAttempts)
        }
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_learner"))
    }

    @Test
    fun `commit 전에 감지한 취소는 신규 데이터를 남기지 않는다`() = withDatabase { db, _, work ->
        runBlocking {
            val task = async(start = CoroutineStart.LAZY) {
                val job = checkNotNull(currentCoroutineContext()[Job])
                work.execute {
                    learners.ensureAndLock(123, nowUtc)
                    userSettings.create(NewUserSettings.fromPolicy(123, policies.loadInitialPolicy(), nowUtc))
                    job.cancel()
                }
            }
            task.start()
            assertFailsWith<CancellationException> { task.await() }
        }
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_learner"))
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM language_learning_user_setting"))
    }

    @Test
    fun `동기 JDBC 블록의 최대 동시 실행 수를 제한한다`() = withDatabase { _, factory, _ ->
        val runner = JdbcTransactionRunner(factory.database, 2)
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        runBlocking {
            withTimeout(30_000) {
                (1..16).map {
                    async {
                        runner.write {
                            val current = active.incrementAndGet()
                            peak.updateAndGet { previous -> maxOf(previous, current) }
                            try {
                                Thread.sleep(20)
                            } finally {
                                active.decrementAndGet()
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        assertTrue(peak.get() in 1..2)
        assertEquals(0, active.get())
    }

    private fun withDatabase(action: (LocalScratchMysql, DatabaseFactory, ExposedSettingsUnitOfWork) -> Unit) {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { factory ->
                action(db, factory, ExposedSettingsUnitOfWork(JdbcTransactionRunner(factory.database, 4), clock))
            }
        }
    }

    private fun execute(db: LocalScratchMysql, sql: String) {
        db.connect().use { it.createStatement().use { statement -> statement.executeUpdate(sql) } }
    }

    private fun scalar(db: LocalScratchMysql, sql: String): Long = db.connect().use {
        it.createStatement()
            .use { statement -> statement.executeQuery(sql).use { row -> check(row.next()); row.getLong(1) } }
    }

    private fun stringScalar(db: LocalScratchMysql, sql: String): String = db.connect().use {
        it.createStatement()
            .use { statement -> statement.executeQuery(sql).use { row -> check(row.next()); row.getString(1) } }
    }
}
