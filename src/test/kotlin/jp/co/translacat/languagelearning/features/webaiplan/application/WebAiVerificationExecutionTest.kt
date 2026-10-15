package jp.co.translacat.languagelearning.features.webaiplan.application

import jp.co.translacat.languagelearning.features.webaiplan.domain.model.WebAiSubmittedItem
import jp.co.translacat.languagelearning.shared.ai.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class WebAiVerificationExecutionTest {
    private val now = Instant.parse("2026-10-15T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `외부 점수를 무시하고 기존 Writing rubric과 독립된 설명 언어를 사용한다`() = runBlocking {
        // 준비: 외부 100점과 해설은 저장하지만 내부 모델에는 보내지 않는다.
        val commands = mutableListOf<ModelExecutionCommand>()
        val execution = WebAiVerificationExecution(ModelExecutionPort { command ->
            commands += command
            output(writingOutput())
        }, clock)
        val item = item("WRITING_TRANSLATION").copy(
            promptText = "고객에게 자료 확인을 정중하게 요청하세요. 반말은 피하세요.",
            sourceText = "자료를 확인해 주세요.", externalScore = 100.0,
            externalFeedbackMarkdown = "EXTERNAL_UNTRUSTED_SCORE_OVERRIDE",
        )

        // 실행
        val verified = execution.verify(input(item))

        // 검증: 의미 cap과 공식 가중치를 기존 Writing과 동일하게 적용한다.
        assertEquals("VERIFIED", verified.status)
        assertEquals(69.0, verified.officialScore)
        assertEquals(listOf(40.0, 90.0, 90.0, 90.0, 90.0), verified.writingScores)
        assertEquals(
            listOf("MEANING", "GRAMMAR", "VOCABULARY", "NATURALNESS", "EXPRESSION"), verified.metrics.map { it.code },
        )
        assertEquals(item, verified.item)
        assertEquals(1, commands.size)
        val command = commands.single()
        assertEquals(ModelTier.MINI, command.tier)
        assertEquals(now.plusSeconds(30), command.deadlineUtc)
        assertFalse(command.messages.single().content.contains("EXTERNAL_UNTRUSTED_SCORE_OVERRIDE"))
        assertFalse(command.messages.single().content.contains("externalScore"))
        assertTrue(command.messages.single().content.contains("Feedback originText fields MUST be written in en"))
        assertTrue(command.messages.single().content.contains("\"sourceLanguage\":\"ko\""))
        assertTrue(command.messages.single().content.contains(item.promptText))
        assertTrue(command.messages.single().content.contains(checkNotNull(item.sourceText)))
        assertEquals("Synthetic explanation", verified.feedback.getValue("explanation").jsonPrimitive.content)
    }

    @Test
    fun `모든 읽기 유형의 근거 점수를 해당 기존 읽기 지표로 기록한다`() = runBlocking {
        // 준비
        var calls = 0
        val execution = WebAiVerificationExecution(ModelExecutionPort {
            calls++
            output(readingOutput(true, 75.5))
        }, clock)
        val codes = mapOf(
            "READING_COMPREHENSION" to "CONTENT", "READING_STRUCTURE" to "STRUCTURE",
            "READING_CONTEXT_INFERENCE" to "CONTEXT_INFERENCE",
        )

        // 실행과 검증: 외부 점수 대신 지문을 재검토한 내부 점수를 사용한다.
        for ((area, metric) in codes) {
            val submitted = item(area).copy(passageText = "Synthetic passage", externalScore = 100.0)
            val result = execution.verify(input(submitted))
            assertEquals("VERIFIED", result.status)
            assertEquals(75.5, result.officialScore)
            assertEquals(metric, result.metrics.single().code)
            assertTrue(result.writingScores.isEmpty())
        }
        assertEquals(3, calls)
    }

    @Test
    fun `음성 근거 없는 여섯 유형과 지문 없는 읽기는 모델 호출과 공식 점수에서 제외한다`() = runBlocking {
        // 준비
        var calls = 0
        val execution = WebAiVerificationExecution(ModelExecutionPort {
            calls++
            error("No model call expected")
        }, clock)
        val areas = listOf(
            "SPEAKING_READ_ALOUD", "SPEAKING_GUIDED", "SPEAKING_FREE",
            "LISTENING_DICTATION", "LISTENING_COMPREHENSION", "LISTENING_SUMMARY",
        )

        // 실행과 검증: 전사 문자열만으로 발음·유창성·청해 평가를 생성하지 않는다.
        for (area in areas) {
            val result = execution.verify(input(item(area).copy(externalScore = 100.0)))
            assertEquals("COACHING", result.status)
            assertEquals("AUDIO_EVIDENCE_REQUIRED", result.reason)
            assertNull(result.officialScore)
            assertTrue(result.metrics.isEmpty())
        }
        val reading = execution.verify(input(item("READING_COMPREHENSION")))
        assertEquals("REJECTED", reading.status)
        assertEquals("READING_PASSAGE_REQUIRED", reading.reason)
        assertEquals(0, calls)
    }

    @Test
    fun `평가 불가 지문과 점수의 모순을 거부하고 호출을 재시도하지 않는다`() = runBlocking {
        // 준비
        var calls = 0
        val execution = WebAiVerificationExecution(ModelExecutionPort {
            calls++
            output(readingOutput(false, 100.0))
        }, clock)

        // 실행
        val failure = assertFailsWith<ModelExecutionFailure> {
            execution.verify(input(item("READING_COMPREHENSION").copy(passageText = "Synthetic passage")))
        }

        // 검증
        assertEquals("WEB_AI_VERIFICATION_SCHEMA_INVALID", failure.code)
        assertEquals(1, calls)
    }

    @Test
    fun `내부 Provider 실패를 외부 점수로 대체하지 않는다`() = runBlocking {
        // 준비
        var calls = 0
        val execution = WebAiVerificationExecution(ModelExecutionPort {
            calls++
            throw ModelExecutionFailure("PROVIDER_TIMEOUT", 504, true)
        }, clock)

        // 실행
        val failure = assertFailsWith<ModelExecutionFailure> {
            execution.verify(input(item("WRITING_GUIDED").copy(externalScore = 100.0)))
        }

        // 검증
        assertEquals("PROVIDER_TIMEOUT", failure.code)
        assertEquals(1, calls)
    }

    @Test
    fun `지문에 없는 근거 인용은 공식 읽기 점수로 채택하지 않는다`() = runBlocking {
        // 준비: 모델이 유효한 지문이라고 주장해도 인용이 실제 지문에 있어야 한다.
        val fabricated = JsonObject(
            readingOutput(true, 100.0) + ("evidenceText" to JsonPrimitive("Fabricated evidence")),
        )
        val execution = WebAiVerificationExecution(ModelExecutionPort { output(fabricated) }, clock)

        // 실행
        val failure = assertFailsWith<ModelExecutionFailure> {
            execution.verify(input(item("READING_COMPREHENSION").copy(passageText = "Synthetic passage")))
        }

        // 검증
        assertEquals("WEB_AI_VERIFICATION_SCHEMA_INVALID", failure.code)
    }

    @Test
    fun `작문 과제 자체의 유효성과 난이도를 같은 호출에서 확인하고 부적절한 과제는 제외한다`() = runBlocking {
        // 준비: 모델이 외부 과제의 검토와 답안 평가를 단일 응답으로 반환한다.
        val checks = listOf(
            Triple(false, true, "WEB_AI_TASK_NOT_EVALUABLE"),
            Triple(true, false, "DIFFICULTY_MISMATCH"),
        )
        for ((evaluable, appropriate, reason) in checks) {
            var calls = 0
            val execution = WebAiVerificationExecution(ModelExecutionPort {
                calls++
                output(writingOutput(evaluable, appropriate))
            }, clock)
            val original = item("WRITING_FREE").copy(externalScore = 100.0,
                externalFeedbackMarkdown = "Original feedback is preserved")

            // 실행
            val verified = execution.verify(input(original))

            // 검증
            assertEquals("REJECTED", verified.status)
            assertEquals(reason, verified.reason)
            assertNull(verified.officialScore)
            assertTrue(verified.metrics.isEmpty())
            assertTrue(verified.writingScores.isEmpty())
            assertEquals(original, verified.item)
            assertEquals(1, calls)
        }
    }

    @Test
    fun `과제 검토를 누락한 작문 점수만으로는 공식 점수를 만들지 않는다`() = runBlocking {
        // 준비
        val inner = writingOutput().getValue("evaluation")
        val execution = WebAiVerificationExecution(ModelExecutionPort { output(inner) }, clock)

        // 실행
        val failure = assertFailsWith<ModelExecutionFailure> {
            execution.verify(input(item("WRITING_TRANSLATION")))
        }

        // 검증
        assertEquals("WEB_AI_VERIFICATION_SCHEMA_INVALID", failure.code)
    }

    @Test
    fun `읽기도 배정 난이도에 맞지 않는 과제는 지문이 있어도 점수에서 제외한다`() = runBlocking {
        // 준비
        var calls = 0
        val execution = WebAiVerificationExecution(ModelExecutionPort {
            calls++
            output(readingOutput(true, null, false))
        }, clock)

        // 실행
        val verified = execution.verify(input(item("READING_COMPREHENSION").copy(passageText = "Synthetic passage")))

        // 검증
        assertEquals("REJECTED", verified.status)
        assertEquals("DIFFICULTY_MISMATCH", verified.reason)
        assertNull(verified.officialScore)
        assertTrue(verified.metrics.isEmpty())
        assertEquals(1, calls)
    }

    private fun item(area: String) = WebAiSubmittedItem(
        itemId = "$area:STANDARD:1", areaCode = area, difficulty = "STANDARD",
        promptText = "Synthetic task", answerText = "Synthetic answer",
    )

    private fun input(item: WebAiSubmittedItem) = WebAiVerificationInput("webai:synthetic", "ko", "ja", "en", item)
    private fun output(data: JsonElement) = ModelExecutionResult(data, 1, 1, "synthetic", "synthetic")
    private fun readingOutput(evaluable: Boolean, score: Double?, appropriate: Boolean = true) = buildJsonObject {
        put("evaluable", evaluable)
        put("difficultyAppropriate", appropriate)
        put("score", score)
        put("summary", "Synthetic summary")
        put("explanation", "Synthetic explanation")
        put("recommendedAnswer", "Synthetic answer")
        put("evidenceText", if (evaluable) JsonPrimitive("Synthetic passage") else JsonNull)
    }

    private fun writingOutput(evaluable: Boolean = true, appropriate: Boolean = true) = buildJsonObject {
        put("taskReview", buildJsonObject {
            put("evaluable", evaluable)
            put("difficultyAppropriate", appropriate)
            put("reason", "Synthetic task review")
        })
        put("evaluation", if (!evaluable || !appropriate) JsonNull else Json.parseToJsonElement(
        """{
          "scores":{"meaning":40,"grammar":90,"vocabulary":90,"naturalness":90,"expression":90},
          "strengths":[{"originText":"Synthetic strength","learningText":"合成の長所"}],
          "weaknesses":[{"originText":"Synthetic improvement","learningText":"合成の改善点"}],
          "corrections":[],
          "recommendedAnswers":["合成回答一","合成回答二"],
          "explanation":{"originText":"Synthetic explanation","learningText":"合成の説明"},
          "profileSignals":{"strengthTags":[],"weaknessTags":[],"grammarPatterns":[],"vocabularyPatterns":[],
            "naturalnessPatterns":[],"expressionPatterns":[],"meaningPatterns":[],"recommendedFocus":[]}
        }""",
        ))
    }
}
