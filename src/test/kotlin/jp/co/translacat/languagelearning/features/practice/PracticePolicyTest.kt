package jp.co.translacat.languagelearning.features.practice

import jp.co.translacat.languagelearning.features.practice.domain.*
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PracticePolicyTest {
    @Test
    fun `Reading 난이도와 skill 보정은 현재 Core 분포를 보존한다`() {
        // 준비
        val expected = listOf("CONTENT", "DETAIL", "INFERENCE", "INFERENCE", "INFERENCE")

        // 실행
        val slots = PracticePolicy.slots("COMPREHENSION", 3)

        // 검증
        assertEquals(listOf(3, 2, 3, 4, 3), slots.map { it.complexityBand })
        assertEquals(expected, slots.map { it.skillTag })
        assertEquals(
            expected,
            PracticePolicy.slots("COMPREHENSION", 4).map { it.skillTag },
        )
        assertEquals(
            listOf("STRUCTURE", "STRUCTURE", "STRUCTURE", "STRUCTURE", "STRUCTURE"),
            PracticePolicy.slots("STRUCTURE", 3).map { it.skillTag },
        )
        assertEquals(2, PracticePolicy.complexityBand(60.0, listOf(54.0)))
        assertEquals(4, PracticePolicy.complexityBand(60.0, listOf(85.0)))
        assertEquals(3, PracticePolicy.complexityBand(null, emptyList()))
    }

    @Test
    fun `퇴역 Vocabulary와 신규 B5 구조 생성은 유지된 정책대로 거부한다`() {
        // 준비 및 실행
        val vocabulary = assertFailsWith<PracticeFailure> {
            PracticePolicy.requireNewLearning(PracticeDomain.VOCABULARY, "CONTEXTUAL_CHOICE", 3)
        }
        val structure = assertFailsWith<PracticeFailure> {
            PracticePolicy.requireNewLearning(PracticeDomain.READING, "STRUCTURE", 4)
        }

        // 검증
        assertEquals(400, vocabulary.status)
        assertEquals("DAILY_VOCABULARY_RETIRED", vocabulary.code)
        assertEquals("READING_B5_STRUCTURE_DEFERRED", structure.code)
        PracticePolicy.requireNewLearning(PracticeDomain.READING, "COMPREHENSION", 5)
    }

    @Test
    fun `재도전은 정답 뒤 거부하고 첫 시도 점수는 별도로 유지한다`() {
        // 준비
        val question = content(1)
        val wrong = PracticeAttempt(1, 1, 1, listOf("B"), false, LocalDateTime.of(2026, 9, 26, 0, 0))

        // 실행
        val normalized = PracticePolicy.validateAnswer(question, listOf(" A "), listOf(wrong))
        val correct = wrong.copy(id = 2, attemptNo = 2, answer = normalized, correct = true)

        // 검증
        assertEquals(listOf("A"), normalized)
        assertFailsWith<PracticeFailure> {
            PracticePolicy.validateAnswer(
                question, listOf("A"), listOf(wrong, correct),
            )
        }
        assertEquals(
            0.0,
            PracticePolicy.metrics(listOf(PracticeQuestion(1, 1, question)), listOf(wrong, correct)).single().score,
        )
        assertEquals(32.5, VocabularyMastery(1, "key", "display").evaluated(false).score)
    }

    @Test
    fun `표현은 해당 지문의 모든 문항에 첫 답변이 생긴 뒤 공개한다`() {
        // 준비
        val questions = (1..3).map { PracticeQuestion(it.toLong(), 1, content(it)) }

        // 실행
        val incomplete = PracticePolicy.completedPassages(questions, setOf(1, 2))
        val complete = PracticePolicy.completedPassages(questions, setOf(1, 2, 3))

        // 검증
        assertTrue(incomplete.isEmpty())
        assertEquals(setOf("p1"), complete)
        assertEquals(
            listOf("Synthetic"),
            PracticePolicy.candidates(
                content(1).copy(vocabularyCandidates = listOf("missing", "Synthetic", "Synthetic")),
            ),
        )
    }

    private fun content(order: Int) = PracticeQuestionContent(
        order, PracticeQuestionType.SINGLE_CHOICE,
        PracticeDifficulty.CURRENT, 3, "p1", "Synthetic passage.", "Synthetic question",
        listOf("A", "B", "C", "D").map { PracticeOption(it, "Option $it") }, listOf("A"), "DETAIL",
        explanationOrigin = "합성 해설", explanationLearning = "Synthetic explanation",
    )
}
