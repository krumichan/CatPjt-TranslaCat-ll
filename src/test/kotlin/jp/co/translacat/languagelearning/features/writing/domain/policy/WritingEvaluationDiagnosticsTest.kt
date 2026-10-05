package jp.co.translacat.languagelearning.features.writing.domain.policy

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import jp.co.translacat.languagelearning.features.writing.application.WritingEvaluationExecution
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionPort
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionCommand
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WritingEvaluationDiagnosticsTest {
    @Test
    fun `모델 요청은 Parser와 같은 닫힌 객체와 점수 및 문자열 제한을 엄격히 요구한다`() = runBlocking {
        // 준비: 실제 Provider 없이 모델 실행 경계로 보내는 계약을 포착한다.
        var command: ModelExecutionCommand? = null
        val model = ModelExecutionPort {
            command = it
            ModelExecutionResult(payload("Original"), 1, 1, "test", "test")
        }

        // 실행
        WritingEvaluationExecution(model).evaluate(
            "schema-test", "LEVEL_TEST", "ko", "en", "{}", Instant.now().plusSeconds(10),
        )

        // 검증: 구조와 값 범위는 강화하되 점수 rubric·필수 응답 필드는 바꾸지 않는다.
        val submitted = checkNotNull(command)
        assertTrue(submitted.strict)
        val schema = checkNotNull(submitted.responseSchema)
        assertClosedObjects(schema)
        val properties = schema.getValue("properties").jsonObject
        properties.getValue("scores").jsonObject.getValue("properties").jsonObject.values.forEach {
            assertEquals(0, it.jsonObject.getValue("minimum").jsonPrimitive.int)
            assertEquals(100, it.jsonObject.getValue("maximum").jsonPrimitive.int)
        }
        val correction = properties.getValue("corrections").jsonObject.getValue("items").jsonObject
            .getValue("properties").jsonObject
        listOf("original", "corrected").forEach {
            assertEquals("\\S", correction.getValue(it).jsonObject.getValue("pattern").jsonPrimitive.content)
        }
    }

    @Test
    fun `빈 교정 원문은 기존 오류로 거부하고 값 없는 필드 경로만 제공한다`() {
        // 준비: 추가 교정을 빈 원문으로 표현한 Provider 응답을 구성한다.
        val payload = payload("")

        // 실행: 기존 nonempty 검증은 그대로 유지한다.
        val failure = assertFailsWith<WritingEvaluationProtocolException> {
            WritingEvaluationParser.parse(payload)
        }

        // 검증: 공개 오류 코드와 비공개 원문 없는 진단 위치를 구분한다.
        assertEquals("WRITING_EVALUATION_SCHEMA_INVALID", failure.code)
        assertEquals("corrections[0].original", failure.validationPath)
        assertEquals(failure.code, failure.message)
    }

    @Test
    fun `정상 교정 응답은 변환하지 않고 기존 점수와 함께 반환한다`() {
        // 준비
        val payload = payload("An original fragment")

        // 실행
        val result = WritingEvaluationParser.parse(payload)

        // 검증
        assertEquals(payload, result.payload)
        assertEquals(80, result.scores.overall)
    }

    @Test
    fun `검사 실패 로그는 Provider 답변과 요청 정보를 노출하지 않는다`() = runBlocking {
        // 준비: 민감한 내용처럼 취급할 합성 값과 메모리 로그 수집기를 사용한다.
        val secret = "private-provider-fragment"
        val model = ModelExecutionPort { ModelExecutionResult(payload("", secret), 1, 1, "test", "test") }
        val logger = LoggerFactory.getLogger(WritingEvaluationExecution::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)

        try {
            // 실행
            assertFailsWith<WritingEvaluationProtocolException> {
                WritingEvaluationExecution(model).evaluate(
                    "private-trace", "LEVEL_TEST", "ko", "en", "{}", Instant.now().plusSeconds(10),
                )
            }

            // 검증: 고정 필드 경로만 남기고 본문·trace·예외 stack은 남기지 않는다.
            val event = appender.list.single()
            assertEquals("Writing evaluation schema rejected. field=corrections[0].original", event.formattedMessage)
            assertFalse(event.formattedMessage.contains(secret))
            assertFalse(event.formattedMessage.contains("private-trace"))
            assertEquals(null, event.throwableProxy)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun payload(original: String, corrected: String = "A corrected fragment") = buildJsonObject {
        put("scores", buildJsonObject {
            listOf("meaning", "grammar", "vocabulary", "naturalness", "expression").forEach { put(it, 80) }
        })
        put("strengths", JsonArray(emptyList()))
        put("weaknesses", JsonArray(emptyList()))
        put("corrections", buildJsonArray {
            add(buildJsonObject {
                put("original", original)
                put("corrected", corrected)
                put("category", "GRAMMAR")
                put("explanation", bilingual())
            })
        })
        put("recommendedAnswers", buildJsonArray { add("First answer"); add("Second answer") })
        put("explanation", bilingual())
        put("profileSignals", buildJsonObject {})
    }

    private fun bilingual() = buildJsonObject { put("originText", "설명"); put("learningText", "Explanation") }

    private fun assertClosedObjects(value: JsonElement) {
        when (value) {
            is JsonObject -> {
                if (value["type"]?.jsonPrimitive?.content == "object") {
                    assertEquals(JsonPrimitive(false), value["additionalProperties"])
                }
                value.values.forEach(::assertClosedObjects)
            }
            is JsonArray -> value.forEach(::assertClosedObjects)
            else -> Unit
        }
    }
}
