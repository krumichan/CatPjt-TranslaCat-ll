package jp.co.translacat.languagelearning.features.autopractice.infrastructure

import jp.co.translacat.languagelearning.features.autopractice.application.*
import jp.co.translacat.languagelearning.features.autopractice.domain.*
import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.repository.ExposedLearnerRepository
import jp.co.translacat.languagelearning.features.leveltest.application.LevelSessionService
import jp.co.translacat.languagelearning.features.leveltest.infrastructure.persistence.ExposedLevelTestUnitOfWork
import jp.co.translacat.languagelearning.features.leveltest.support.TestLevelContext
import jp.co.translacat.languagelearning.features.practice.domain.*
import jp.co.translacat.languagelearning.features.practice.application.PracticeReadService
import jp.co.translacat.languagelearning.features.practice.infrastructure.ExposedPracticeUnitOfWork
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsResult
import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsSnapshot
import jp.co.translacat.languagelearning.features.settings.domain.model.ConfiguredLanguagePair
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import jp.co.translacat.languagelearning.support.SettingsFixtures
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.*

/** MySQL의 실제 unique 키·lease와 원본 답안 조회를 검증한다. */
class AutoPracticePersistenceIntegrationTest {
    @Test
    fun oneAssignmentSurvivesPlanChangesAndAnOlderSetCountsOnTheActualAnswerDay() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { factory ->
                runBlocking {
                    val runner = JdbcTransactionRunner(factory.database, 4)
                    val store = JdbcAutoPracticeStore(runner) { owner, now ->
                        ExposedLearnerRepository {}.ensureAndLock(owner, now).requireActive()
                    }
                    val monday = LocalDateTime.parse("2026-11-30T10:00:00")
                    val clock = Clock.fixed(monday.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
                    LevelSessionService(ExposedLevelTestUnitOfWork(runner, clock), TestLevelContext())
                        .selfSelect(15, "B2", "initial-level")
                    val area = AutoPracticeArea.READING_COMPREHENSION
                    val plan = AutoPracticePlan(
                        "9453b8cc-6bdc-4ed5-ae14-ccde5062c915", 1, "Morning", "00:00",
                        mapOf(area to AutoPracticeQuantity(standard = 1)), "ko", "ja", true,
                    )
                    val tuesday = monday.toLocalDate().plusDays(1)
                    val wednesday = monday.plusDays(2)

                    // 준비·실행: 예약 회차를 두 번 쓰고, 이월된 세트에 실제 수요일 제출을 남긴다.
                    store.writeOwner(15, monday) {
                        writePlan(15, plan, monday, monday, false)
                        reserve(15, plan, tuesday, monday)
                        reserve(15, plan.copy(revision = 2), tuesday, monday)
                    }
                    ExposedPracticeUnitOfWork(runner).write(15) {
                        val scoped = records.scoped("ko", "ja")
                        val set = scoped.save(PracticeSet(
                            0, 15, tuesday, PracticeDomain.READING, "COMPREHENSION", 2, 1,
                            """{"originLanguage":"ko","learningLanguage":"ja"}""", startedAt = monday,
                        ))
                        val question = scoped.addQuestion(set.id, PracticeQuestionContent(
                            1, PracticeQuestionType.SINGLE_CHOICE, PracticeDifficulty.CURRENT, 2,
                            prompt = "質問", options = listOf(PracticeOption("A", "答え")), correctAnswer = listOf("A"),
                            skillTag = "CONTENT", explanationOrigin = "설명", explanationLearning = "説明",
                        ))
                        scoped.addAttempt(PracticeAttempt(0, question.id, 1, listOf("A"), true, wednesday))
                    }

                    // 검증: 날짜·언어가 일치하는 실제 제출만 읽고 배정과 lease는 한 번만 확정한다.
                    store.writeOwner(15, monday) {
                        assertEquals(monday, placement(15, "ko", "ja"))
                        assertEquals(listOf(wednesday), submissions(15, "ko", "ja", wednesday.minusHours(1),
                            wednesday.plusHours(1)))
                        assertTrue(submissions(15, "ko", "en", monday, wednesday.plusDays(1)).isEmpty())
                        val job = pending(monday).single()
                        assertEquals(tuesday.toString(), job.job.learningDate)
                        assertEquals(1L, job.job.planRevision)
                        assertTrue(claim(job, monday, "lease"))
                        assertFalse(claim(job, monday, "wrong"))
                        finish(job, "wrong", "QUEUED", monday, "wrong", null)
                        finish(job, "lease", "QUEUED", monday, "resource", null)
                        assertEquals("resource", assignments(15, "ko", "ja", tuesday, tuesday).single().resourceId)
                        assertEquals(12, limits().size)
                    }
                }
            }
        }
    }

    /** 実 DB で同日手動→自動の衝突と深夜繰越を通し、ネイティブの ID 直指定も確認する。 */
    @Test
    fun manualRoundIsDispatchedOnceAndMidnightPublicationUsesTheSameNativeSet() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { factory ->
                runBlocking {
                    val runner = JdbcTransactionRunner(factory.database, 4)
                    val start = LocalDate.of(2026, 12, 1)
                    val time = ScenarioClock(start.atTime(10, 0))
                    val store = JdbcAutoPracticeStore(runner, time) { owner, now ->
                        ExposedLearnerRepository {}.ensureAndLock(owner, now).requireActive()
                    }
                    val worker = TestPublisher(runner, time)
                    val area = AutoPracticeArea.READING_COMPREHENSION
                    val service = AutoPracticeService(store, TestSettings(time), mapOf(area to worker), time)
                    val reading = PracticeReadService(ExposedPracticeUnitOfWork(runner, time), store)

                    // 18時の翌日分を自動準備する条件が成立していても、14時の手動回だけを使う。
                    LevelSessionService(ExposedLevelTestUnitOfWork(runner, time), TestLevelContext())
                        .selfSelect(20, "B2", "daytime-placement")
                    time.at(start.plusDays(1).atTime(14, 0))
                    service.save(20, null, planInput("18:00", area))
                    val first = async { service.prepareManually(20) }
                    val duplicate = async { service.prepareManually(20) }
                    assertEquals(first.await().jobs.single().id, duplicate.await().jobs.single().id)
                    service.tick { throw it }
                    time.at(start.plusDays(1).atTime(15, 0))
                    service.tick { throw it }
                    assertEquals(1, worker.enqueues.count { it.first == 20L })
                    assertNull(service.workspace(20).manual)

                    // 20:20開始の未公開回は21時にそのまま翌日へ移り、22時に完成しても0時まで読めない。
                    LevelSessionService(ExposedLevelTestUnitOfWork(runner, time), TestLevelContext())
                        .selfSelect(21, "B2", "midnight-placement")
                    service.save(21, null, planInput("00:00", area))
                    time.at(start.plusDays(1).atTime(20, 20))
                    val day = service.prepareManually(21).jobs.single()
                    service.tick { throw it }
                    val id = worker.nativeSets.getValue(day.id)
                    time.at(start.plusDays(1).atTime(21, 0))
                    service.tick { throw it }
                    time.at(start.plusDays(1).atTime(22, 0))
                    worker.publish(store, 21, day.id)
                    val blocked = service.workspace(21).jobs.single()
                    assertEquals(day.id, blocked.id)
                    assertEquals(start.plusDays(2).toString(), blocked.learningDate)
                    assertEquals("LOCKED", blocked.state)
                    assertNull(blocked.resourceId)
                    assertNull(service.prepareManually(21).manual)
                    assertEquals("AUTO_PRACTICE_NOT_OPEN", assertFailsWith<LearningBusinessException> {
                        reading.get(21, id)
                    }.code)
                    time.at(start.plusDays(2).atStartOfDay())
                    service.tick { throw it }
                    val open = service.workspace(21).jobs.single()
                    assertEquals(LearningPublicId.encode(id).toString(), open.resourceId)
                    assertEquals(start.plusDays(2).toString(), reading.get(21, id)["learningDate"]?.jsonPrimitive?.content)
                    assertEquals(1, worker.enqueues.count { it.first == 21L })
                }
            }
        }
    }

    /** カットオフ時点の資格と、既に公開可能な問題を持つ回は繰越と区別する。 */
    @Test
    fun unqualifiedTomorrowAndAlreadyPlayableManualRoundStayToday() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { factory ->
                runBlocking {
                    val runner = JdbcTransactionRunner(factory.database, 4)
                    val today = LocalDate.of(2026, 12, 1)
                    val time = ScenarioClock(today.minusDays(4).atTime(10, 0))
                    val store = JdbcAutoPracticeStore(runner, time) { owner, now ->
                        ExposedLearnerRepository {}.ensureAndLock(owner, now).requireActive()
                    }
                    LevelSessionService(ExposedLevelTestUnitOfWork(runner, time), TestLevelContext())
                        .selfSelect(22, "B2", "older-placement")
                    time.at(today.atTime(20, 20))
                    LevelSessionService(ExposedLevelTestUnitOfWork(runner, time), TestLevelContext())
                        .selfSelect(23, "B2", "new-placement")
                    val worker = TestPublisher(runner, time)
                    val area = AutoPracticeArea.READING_COMPREHENSION
                    val service = AutoPracticeService(store, TestSettings(time), mapOf(area to worker), time)
                    for (owner in listOf(22L, 23L)) {
                        service.save(owner, null, planInput("00:00", area))
                        service.prepareManually(owner)
                    }
                    service.tick { throw it }
                    val alreadyPlayable = service.workspace(23).jobs.single().id
                    time.at(today.atTime(20, 59))
                    worker.publish(store, 23, alreadyPlayable)

                    // 未資格の未完成回は当日に残る。準備済みの当日回も維持し、明日の別回を作る。
                    time.at(today.atTime(21, 0))
                    service.tick { throw it }
                    assertEquals(today.toString(), service.workspace(22).jobs.single().learningDate)
                    assertEquals(1, worker.enqueues.count { it.first == 22L })
                    val qualified = service.workspace(23).jobs
                    assertEquals(today.toString(), qualified.single { it.id == alreadyPlayable }.learningDate)
                    assertEquals(today.plusDays(1).toString(), qualified.single { it.id != alreadyPlayable }.learningDate)
                    assertEquals(2, worker.enqueues.count { it.first == 23L })
                }
            }
        }
    }

    /** テストでは一種類だけ選び、生成回数と日付を個別に調べる。 */
    private fun planInput(time: String, area: AutoPracticeArea) =
        AutoPracticePlanInput("Practice", time, mapOf(area to AutoPracticeQuantity(standard = 1)), true)

    private class ScenarioClock(initial: LocalDateTime) : Clock() {
        private val zone = ZoneId.of("Asia/Tokyo")
        private var current = initial.atZone(zone).toInstant()
        override fun instant() = current
        override fun getZone() = zone
        override fun withZone(zone: ZoneId) = fixed(current, zone)

        /** スケジューラーと公開ゲートを同じ時刻に動かす。 */
        fun at(value: LocalDateTime) { current = value.atZone(zone).toInstant() }
    }

    private class TestSettings(private val clock: Clock) : SettingsServiceOperations {
        override suspend fun userSnapshot(userId: Long) = UserSettingsSnapshot(
            userId, LocalDate.now(clock), UserSettingsResult(SettingsFixtures.configured(userId), SettingsFixtures.policy()),
        )
        override suspend fun learningDate(userId: Long) = LocalDate.now(clock)
        override suspend fun adminPolicy() = SettingsFixtures.admin()
        override suspend fun listeningPolicy() = error("Reading-only fixture")
        override suspend fun configuredLanguagePairs() = listOf(ConfiguredLanguagePair("ko", "ja"))
    }

    private class TestPublisher(runner: JdbcTransactionRunner, clock: Clock) : AutoPracticePublisher {
        private val work = ExposedPracticeUnitOfWork(runner, clock)
        val enqueues = mutableListOf<Pair<Long, Long>>()
        val nativeSets = mutableMapOf<Long, Long>()
        private val ready = mutableSetOf<Long>()

        /** 有償モデルの代わりに、実ストレージへネイティブセットと予約 ID を書く。 */
        override suspend fun enqueue(owner: Long, job: AutoPracticeJob): AutoPracticeResource {
            enqueues += owner to job.id
            val id = work.write(owner) {
                records.scoped("ko", "ja").save(PracticeSet(
                    0, owner, LocalDate.parse(job.learningDate), PracticeDomain.READING, "COMPREHENSION", 2, 1,
                    """{"originLanguage":"ko","learningLanguage":"ja","autoPracticeJobId":${job.id}}""", startedAt = nowUtc,
                )).id
            }
            nativeSets[job.id] = id
            return AutoPracticeResource(LearningPublicId.encode(id).toString())
        }

        /** 公開可能の通知も本番と同じ owner ロック・トランザクションで受け渡す。 */
        suspend fun publish(publication: AutoPracticePublication, owner: Long, job: Long) {
            work.write(owner) { publication.markPlayable(owner, job) }
            ready += job
        }

        override suspend fun state(owner: Long, job: AutoPracticeJob) = if (job.id in ready) "READY" else "GENERATING"
    }
}
