package jp.co.translacat.languagelearning.features.listening.domain.policy

import jp.co.translacat.languagelearning.features.listening.application.ListeningEvaluationExecution
import jp.co.translacat.languagelearning.features.listening.application.ListeningGenerationExecution
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningEvaluationContext
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskType
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionCommand
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionPort
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionResult
import jp.co.translacat.languagelearning.shared.ai.ModelTier
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class ListeningExecutionGoldenTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun resource(name: String) = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/contracts/$name"))
            .bufferedReader().use { it.readText() },
    )

    @Test
    fun `생성 prompt schema 후보 채택 결과를 Python과 대조한다`() = runTest {
        // 준비: 실제 Python 정책으로 만든 합성 입력과 결과를 사용한다.
        val fixture = resource("listening-generation-python-golden.json").jsonObject
        val calls = mutableListOf<ModelExecutionCommand>()
        val execution = ListeningGenerationExecution(
            ModelExecutionPort { command ->
                calls += command
                ModelExecutionResult(fixture.getValue("response"), 13, 21, "test", "test")
            },
        )

        // 실행
        val items = execution.generate(fixture.getValue("request").jsonObject, Instant.now().plusSeconds(30))

        // 검증: 현재 후보 수와 호출 상한, provider 입력·최종 metadata를 보존한다.
        assertEquals(1, calls.size)
        assertEquals(ModelTier.LUNA, calls.single().tier)
        assertEquals(8192, calls.single().maxOutputTokens)
        assertPrompt(fixture.getValue("prompt").jsonPrimitive.content, calls.single().messages.single().content)
        assertEquals(fixture.getValue("items"), JsonArray(items))
    }

    @Test
    fun `의미 평가 prompt와 점수 evidence profile 신호를 Python과 대조한다`() = runTest {
        // 준비
        val fixtures = resource("listening-semantic-python-golden.json").jsonArray
        for (entry in fixtures) {
            val fixture = entry.jsonObject
            val calls = mutableListOf<ModelExecutionCommand>()
            val execution = ListeningEvaluationExecution(
                ModelExecutionPort { command ->
                    calls += command
                    ModelExecutionResult(fixture.getValue("response"), 13, 21, "test", "test")
                },
            )

            // 실행
            val result = execution.evaluate(
                ListeningTaskType.valueOf(fixture.getValue("taskType").jsonPrimitive.content),
                fixture.getValue("request").jsonObject, ListeningEvaluationContext(), Instant.now().plusSeconds(60),
            )

            // 검증
            assertEquals(1, calls.size)
            assertEquals(ModelTier.MINI, calls.single().tier)
            assertPrompt(fixture.getValue("prompt").jsonPrimitive.content, calls.single().messages.single().content)
            val actual = json.encodeToJsonElement(result).jsonObject
            val expected = fixture.getValue("task").jsonObject
            for (field in actual.keys) assertEquals(expected[field], actual[field], "$field / ${result.taskType}")
        }
    }

    @Test
    fun `객관식과 요약 모드의 생성 계약도 Python 판정과 같다`() = runTest {
        // 준비: Python 실제 서비스의 각 모드 고정 Provider 출력과 채택 결과이다.
        val fixtures = resource("listening-modes-python-golden.json").jsonObject.getValue("generation").jsonArray
        for (entry in fixtures) {
            val fixture = entry.jsonObject
            val calls = mutableListOf<ModelExecutionCommand>()
            val execution = ListeningGenerationExecution(
                ModelExecutionPort { command ->
                    calls += command
                    ModelExecutionResult(fixture.getValue("response"), 1, 1, "test", "test")
                },
            )

            // 실행
            val items = execution.generate(fixture.getValue("request").jsonObject, Instant.now().plusSeconds(30))

            // 검증
            assertEquals(1, calls.size)
            assertPrompt(fixture.getValue("prompt").jsonPrimitive.content, calls.single().messages.single().content)
            assertEquals(fixture.getValue("items"), JsonArray(items))
        }
    }

    @Test
    fun `잘못된 교차 필드와 중복 metric은 통과시키지 않는다`() {
        // 준비
        val fixture = resource("listening-semantic-python-golden.json").jsonArray.first().jsonObject
        val response = fixture.getValue("response").jsonObject
        val request = fixture.getValue("request").jsonObject
        val units = request.getValue("keyMeaningUnits").jsonArray.map { it.jsonPrimitive.content }
        val invalidAnchor =
            JsonObject(response + ("deliveredMeaningUnits" to JsonArray(listOf(JsonPrimitive("invented-unit")))))
        val duplicate =
            JsonObject(response + ("metrics" to JsonArray(List(3) { response.getValue("metrics").jsonArray.first() })))

        // 실행 및 검증
        assertFailsWith<ListeningProtocolFailure> {
            ListeningSemanticEvaluation.parse(
                ListeningTaskType.INTERPRETATION, invalidAnchor, units, ListeningEvaluationContext(),
            )
        }
        assertFailsWith<ListeningProtocolFailure> {
            ListeningSemanticEvaluation.parse(
                ListeningTaskType.INTERPRETATION, duplicate, units, ListeningEvaluationContext(),
            )
        }
    }

    private fun assertPrompt(expected: String, actual: String) {
        val expectedBody = expected.substringAfterLast("\n\n")
        val actualBody = actual.substringAfterLast("\n\n")
        assertEquals(expected.substringBeforeLast("\n\n"), actual.substringBeforeLast("\n\n"))
        assertEquals(Json.parseToJsonElement(expectedBody), Json.parseToJsonElement(actualBody))
    }
}
