package jp.co.translacat.languagelearning.features.practice

import jp.co.translacat.languagelearning.features.practice.application.*
import jp.co.translacat.languagelearning.features.practice.domain.*
import jp.co.translacat.languagelearning.features.practice.infrastructure.ExposedPracticeUnitOfWork
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.*
import kotlin.test.*

class PracticeStateIntegrationTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-26T04:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `지문 공개 답변과 Growth 완료는 실제 DB에서 단 한 번 반영된다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 실제 테이블에 작업을 만들고 두 지문 묶음을 게시한다.
                val work = work(factory)
                val state = PracticeGenerationState(work)
                val set = seed(work, 101)
                assertTrue(state.publish(assertNotNull(state.claim(101, set.id)), bundle(1)))
                assertTrue(state.publish(assertNotNull(state.claim(101, set.id)), bundle(4)))
                val questions = work.read { records.questions(set.id) }
                val answers = PracticeAnswerService(work)

                // 실행: 동일한 정답 동시 제출은 한 건만 성공한다.
                val duplicate = listOf(
                    async { runCatching { answers.submit(101, questions[0].id, listOf("A")) } },
                    async { runCatching { answers.submit(101, questions[0].id, listOf("A")) } },
                ).awaitAll()
                questions.drop(1).forEach { answers.submit(101, it.id, listOf("A")) }

                // 검증: 첫 시도와 Activity·완료점수가 같은 트랜잭션으로 저장된다.
                assertEquals(1, duplicate.count { it.isSuccess })
                assertEquals(
                    "LANGUAGE_LEARNING_ANSWER_NOT_ALLOWED",
                    (duplicate.single { it.isFailure }.exceptionOrNull() as PracticeFailure).code,
                )
                work.read {
                    assertEquals(5, records.attempts(set.id).size)
                    assertEquals(100.0, records.find(101, set.id)?.officialScore)
                    assertEquals(PracticeStatus.COMPLETED, records.find(101, set.id)?.status)
                    assertEquals(100.0, growth.activity(101, "READING", "ll-practice-${set.id}")?.overallScore)
                    assertTrue(records.metrics(set.id).all { it.score == 100.0 })
                }
                assertFailsWith<PracticeFailure> { answers.submit(202, questions[0].id, listOf("A")) }
            }
        }
    }

    @Test
    fun `재기동 lease 회수는 늦은 결과를 거부하고 실패해도 기존 답변을 보존한다`() = LocalScratchMysql.use { db ->
        var setId = 0L
        var old: PracticeGenerationClaim? = null
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비
                val work = work(factory)
                val state = PracticeGenerationState(work)
                setId = seed(work, 303).id
                assertTrue(state.publish(assertNotNull(state.claim(303, setId)), bundle(1)))
                val question = work.read { records.questions(setId).first() }
                PracticeAnswerService(work).submit(303, question.id, listOf("B"))
                old = assertNotNull(state.claim(303, setId))
            }
        }
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 실행: 새 pool과 만료된 시각으로 진행 중 지문의 작업 소유권을 회수한다.
                val work = work(factory, Clock.offset(clock, Duration.ofMinutes(31)))
                val state = PracticeGenerationState(work)
                val current = assertNotNull(state.claim(303, setId))
                val late = state.publish(assertNotNull(old), bundle(4))
                state.fail(current, "AI_SCHEMA_INVALID", false)

                // 검증
                assertFalse(late)
                work.read {
                    assertEquals(PracticeGenerationStatus.PARTIAL, records.find(303, setId)?.generationStatus)
                    assertEquals(3, records.questions(setId).size)
                    assertEquals(listOf("B"), records.attempts(setId).single().answer)
                }
                assertEquals(PracticeGenerationStatus.PENDING, state.retry(303, setId).generationStatus)
            }
        }
    }

    @Test
    fun `잘못된 묶음은 부분 문항을 남기지 않고 rollback된다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비
                val work = work(factory)
                val state = PracticeGenerationState(work)
                val set = seed(work, 404)
                val claim = assertNotNull(state.claim(404, set.id))
                val valid = bundle(1)
                val broken = valid.copy(
                    questions = valid.questions.mapIndexed { index, question ->
                        if (index == 2) question.copy(correctAnswer = listOf("Z")) else question
                    },
                )

                // 실행 및 검증
                assertFailsWith<PracticeFailure> { state.publish(claim, broken) }
                assertTrue(work.read { records.questions(set.id).isEmpty() })
                assertTrue(state.publish(claim, valid))
                assertEquals(3, work.read { records.questions(set.id).size })
            }
        }
    }

    private suspend fun seed(work: PracticeUnitOfWork, userId: Long) = work.write(userId) {
        records.save(
            PracticeSet(
                0, userId, LocalDate.parse("2026-09-26"), PracticeDomain.READING,
                "COMPREHENSION", 3, 5, "{}", startedAt = nowUtc,
            ),
        )
    }

    private fun work(factory: DatabaseFactory, at: Clock = clock) =
        ExposedPracticeUnitOfWork(JdbcTransactionRunner(factory.database, 4), at)

    private fun bundle(first: Int): ReadingGeneratedBundle {
        val slots =
            PracticePolicy.slots("COMPREHENSION", 3).filter { it.globalOrder in (if (first == 1) 1..3 else 4..5) }
        return ReadingGeneratedBundle(
            "reading-vocabulary-generation",
            slots.map { slot ->
                PracticeQuestionContent(
                    slot.globalOrder, PracticeQuestionType.SINGLE_CHOICE, slot.difficulty,
                    slot.complexityBand, if (first == 1) "p1" else "p2", "Synthetic passage.",
                    "Synthetic question ${slot.globalOrder}",
                    listOf("A", "B", "C", "D").map { PracticeOption(it, "Option $it") }, listOf("A"), slot.skillTag,
                    explanationOrigin = "합성 해설", explanationLearning = "Synthetic explanation",
                )
            },
        )
    }
}
