package jp.co.translacat.languagelearning.features.autopractice.infrastructure

import jp.co.translacat.languagelearning.features.autopractice.domain.*
import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.repository.ExposedLearnerRepository
import jp.co.translacat.languagelearning.features.leveltest.application.LevelSessionService
import jp.co.translacat.languagelearning.features.leveltest.infrastructure.persistence.ExposedLevelTestUnitOfWork
import jp.co.translacat.languagelearning.features.leveltest.support.TestLevelContext
import jp.co.translacat.languagelearning.features.practice.domain.*
import jp.co.translacat.languagelearning.features.practice.infrastructure.ExposedPracticeUnitOfWork
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.LocalDateTime
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
}
