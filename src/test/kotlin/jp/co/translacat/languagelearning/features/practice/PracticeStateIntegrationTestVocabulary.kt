package jp.co.translacat.languagelearning.features.practice

import jp.co.translacat.languagelearning.features.practice.application.PracticeAnswerService
import jp.co.translacat.languagelearning.features.practice.application.PracticeReadService
import jp.co.translacat.languagelearning.features.practice.domain.*
import jp.co.translacat.languagelearning.features.practice.infrastructure.ExposedPracticeUnitOfWork
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*

class PracticeStateIntegrationTestVocabulary {
    private val clock = Clock.fixed(Instant.parse("2026-09-26T04:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `기존 Vocabulary 첫 답변과 복습 mastery는 공식 시도만 반영하고 사용자별로 격리한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 신규 출제는 실행하지 않고 기존 10문항 세트와 과거 오답 표현을 실제 DB에 보존한다.
                val work = ExposedPracticeUnitOfWork(JdbcTransactionRunner(factory.database, 4), clock)
                val set = work.write(701) {
                    records.saveMastery(
                        VocabularyMastery(
                            701, "review-key", "Review expression", 32.5, 1, 2,
                            LocalDate.parse("2026-09-24"),
                        ),
                    )
                    records.saveMastery(VocabularyMastery(701, "unanswered-key", "New expression"))
                    val saved = records.save(
                        PracticeSet(
                            0, 701, LocalDate.parse("2026-09-25"), PracticeDomain.VOCABULARY,
                            "CONTEXTUAL_CHOICE", 3, 10, "{}", generationStatus = PracticeGenerationStatus.READY,
                            startedAt = nowUtc,
                        ),
                    )
                    (1..10).forEach { order -> records.addQuestion(saved.id, question(order)) }
                    saved
                }
                work.write(702) {
                    records.saveMastery(
                        VocabularyMastery(702, "review-key", "Other expression", 90.0, 4),
                    )
                }
                val answers = PracticeAnswerService(work)
                val reads = PracticeReadService(work)
                val questions = work.read { records.questions(set.id) }
                val originalOther = reads.mastery(702)
                assertTrue(
                    reads.get(701, set.id).getValue("questions").jsonArray.all {
                        it.jsonObject.getValue("correctAnswer").jsonArray.isEmpty()
                    },
                )

                // 실행: 오답 뒤 재도전과 기존 복습 표현의 동시 정답을 실제 잠금·트랜잭션으로 처리한다.
                val first = answers.submit(701, questions[0].id, listOf("B"))
                val retry = answers.submit(701, questions[0].id, listOf("A"))
                val duplicates =
                    (1..2).map { async { runCatching { answers.submit(701, questions[1].id, listOf("A")) } } }
                        .awaitAll()
                questions.drop(2).forEach { answers.submit(701, it.id, listOf("A")) }

                // 검증: 가중치 0.35·반올림·첫 시도 점수와 복습의 별도 공식 시도를 원본대로 유지한다.
                assertTrue(first.official)
                assertFalse(retry.official)
                assertEquals(2, retry.attemptNo)
                assertEquals(1, duplicates.count { it.isSuccess })
                assertEquals(
                    "LANGUAGE_LEARNING_ANSWER_NOT_ALLOWED",
                    (duplicates.single { it.isFailure }.exceptionOrNull() as PracticeFailure).code,
                )
                val denied = assertFailsWith<PracticeFailure> { answers.submit(701, questions[0].id, listOf("A")) }
                assertEquals("LANGUAGE_LEARNING_ANSWER_NOT_ALLOWED", denied.code)
                assertEquals(
                    "LANGUAGE_LEARNING_DAILY_ITEM_NOT_FOUND",
                    assertFailsWith<PracticeFailure> { answers.submit(702, questions[0].id, listOf("A")) }.code,
                )
                assertEquals(
                    "LANGUAGE_LEARNING_DAILY_SET_NOT_FOUND",
                    assertFailsWith<PracticeFailure> { reads.get(702, set.id) }.code,
                )
                work.read {
                    val values = records.masteries(701).associateBy { it.canonicalKey }
                    assertEquals(32.5, values.getValue("key-1").score)
                    assertEquals(1, values.getValue("key-1").evaluationCount)
                    assertEquals(56.13, values.getValue("review-key").score)
                    assertEquals(2, values.getValue("review-key").evaluationCount)
                    assertEquals(2, values.getValue("review-key").selectedCount)
                    assertEquals(LocalDate.parse("2026-09-24"), values.getValue("review-key").lastSelectedDate)
                    assertEquals(11, records.attempts(set.id).size)
                    assertEquals(90.0, records.find(701, set.id)?.officialScore)
                    assertEquals(90.0, growth.activity(701, "VOCABULARY", "ll-practice-${set.id}")?.overallScore)
                    assertEquals(10, records.metrics(set.id).single().sampleCount)
                }
                val summary = reads.mastery(701)
                assertEquals(11, summary.getValue("total").jsonPrimitive.int)
                assertEquals(62.86, summary.getValue("averageScore").jsonPrimitive.double)
                assertEquals(1, summary.getValue("newCount").jsonPrimitive.int)
                assertEquals(1, summary.getValue("learningCount").jsonPrimitive.int)
                assertEquals(9, summary.getValue("familiarCount").jsonPrimitive.int)
                assertEquals(10, summary.getValue("weakest").jsonArray.size)
                assertEquals(originalOther, reads.mastery(702))
                assertTrue(
                    reads.get(701, set.id).getValue("questions").jsonArray[1].jsonObject.getValue(
                        "reviewTarget",
                    ).jsonPrimitive.boolean,
                )
            }
        }
    }

    @Test
    fun `mastery 요약은 원본 단계 경계와 미평가 제외 및 취약 표현 20개 상한을 유지한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 경계 점수와 미평가 표현을 독립 사용자에게 저장한다.
                val work = ExposedPracticeUnitOfWork(JdbcTransactionRunner(factory.database, 4), clock)
                work.write(703) {
                    records.saveMastery(VocabularyMastery(703, "new", "New"))
                    listOf(54.99, 55.0, 69.99, 70.0, 84.99, 85.0).forEachIndexed { index, score ->
                        records.saveMastery(VocabularyMastery(703, "boundary-$index", "Boundary $index", score, 1))
                    }
                    (1..20).forEach { records.saveMastery(VocabularyMastery(703, "strong-$it", "Strong $it", 80.0, 1)) }
                }

                // 실행
                val summary = PracticeReadService(work).mastery(703)

                // 검증: NEW는 평균·취약 목록에서 제외되며 같은 원본 단계 기준으로 집계한다.
                assertEquals(27, summary.getValue("total").jsonPrimitive.int)
                assertEquals(77.69, summary.getValue("averageScore").jsonPrimitive.double)
                mapOf(
                    "newCount" to 1, "learningCount" to 1, "familiarCount" to 2, "strongCount" to 22,
                    "masteredCount" to 1,
                )
                    .forEach { (key, count) -> assertEquals(count, summary.getValue(key).jsonPrimitive.int) }
                val weakest = summary.getValue("weakest").jsonArray
                assertEquals(20, weakest.size)
                assertEquals("boundary-0", weakest.first().jsonObject.getValue("canonicalKey").jsonPrimitive.content)
                assertTrue(weakest.none { it.jsonObject.getValue("canonicalKey").jsonPrimitive.content == "new" })
            }
        }
    }

    private fun question(order: Int) = PracticeQuestionContent(
        order, PracticeQuestionType.SINGLE_CHOICE, PracticeDifficulty.CURRENT, 3,
        prompt = "Synthetic vocabulary question $order",
        options = listOf("A", "B", "C", "D").map { PracticeOption(it, "Option $it") },
        correctAnswer = listOf("A"), skillTag = "MEANING", explanationOrigin = "합성 해설",
        explanationLearning = "Synthetic explanation",
        targetExpression = if (order == 2) "Review expression" else "Expression $order",
        canonicalKey = if (order == 2) "review-key" else "key-$order", reviewTarget = order == 2,
    )
}
