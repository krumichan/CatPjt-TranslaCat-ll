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
import kotlin.test.assertTrue

internal class ListeningExecutionGoldenTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun resource(name: String) = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/contracts/$name"))
            .bufferedReader().use { it.readText() },
    )

    @Test
    fun `한국어와 일본어 사용자의 영어 듣기 세 모드는 영문 원문과 설명 언어를 유지한다`() = runTest {
        // 준비: 기존 실제 실행 입력의 구조를 사용하고 요청 언어와 모델의 영문 콘텐츠만 바꾼다.
        val fixture = resource("listening-generation-python-golden.json").jsonObject
        val base = fixture.getValue("request").jsonObject
        val originalItem = fixture.getValue("response").jsonObject.getValue("items").jsonArray.first().jsonObject
        val source = "I would like to visit a quiet temple in Kyoto after lunch."
        val scenarios = listOf("ko" to "DICTATION", "ko" to "COMPREHENSION", "ko" to "SUMMARY",
            "ja" to "DICTATION", "ja" to "COMPREHENSION", "ja" to "SUMMARY")
        for ((origin, mode) in scenarios) {
            val request = JsonObject(base + mapOf(
                "userContext" to JsonObject(base.getValue("userContext").jsonObject + mapOf(
                    "originLanguage" to JsonPrimitive(origin), "learningLanguage" to JsonPrimitive("en"),
                )),
                "setContext" to JsonObject(base.getValue("setContext").jsonObject + mapOf(
                    "learningMode" to JsonPrimitive(mode), "itemCount" to JsonPrimitive(1),
                    "selectedKeywords" to JsonArray(emptyList()),
                )),
            ))
            val meanings = if (origin == "ko") listOf("점심 후 교토의 조용한 절을 방문하고 싶습니다.",
                "점심을 먹고 교토의 한적한 사찰을 둘러보고 싶어요.")
                else listOf("昼食後に京都の静かなお寺を訪れたいです。", "お昼を食べてから京都の落ち着いたお寺を見たいです。")
            val units = if (origin == "ko") listOf("교토의 조용한 절", "점심 후 방문 희망")
                else listOf("京都の静かなお寺", "昼食後の訪問希望")
            val item = JsonObject(originalItem + buildJsonObject {
                put("sourceText", source)
                put("referenceMeanings", JsonArray(meanings.map(::JsonPrimitive)))
                put("keyMeaningUnits", JsonArray(units.map(::JsonPrimitive)))
                put("targetKeywords", JsonArray(listOf(JsonPrimitive("temple"), JsonPrimitive("Kyoto"))))
                if (mode == "COMPREHENSION") {
                    put("question", "What would the speaker like to visit?")
                    put("options", JsonArray(listOf("A" to "A quiet temple", "B" to "A busy market",
                        "C" to "A hotel", "D" to "A station").map { (key, text) ->
                        buildJsonObject { put("key", key); put("text", text) }
                    }))
                    put("correctOptionKey", "A")
                    put("comprehensionFocus", "DETAIL")
                }
                if (mode == "SUMMARY") put("summaryKeyPoints", JsonArray(units.map(::JsonPrimitive)))
            })
            val commands = mutableListOf<ModelExecutionCommand>()
            val execution = ListeningGenerationExecution(ModelExecutionPort { command ->
                commands += command
                ModelExecutionResult(buildJsonObject { put("items", JsonArray(listOf(item))) },
                    10, 10, "synthetic", "english-contract")
            })

            // 실행
            val generated = execution.generate(request, Instant.now().plusSeconds(30))

            // 검증: 영어를 일본어로 대체하지 않고 모드별 항목 및 원문 언어 설명을 그대로 반환한다.
            assertEquals(source, generated.single().getValue("sourceText").jsonPrimitive.content)
            assertEquals(JsonArray(meanings.map(::JsonPrimitive)), generated.single()["referenceMeanings"])
            assertEquals("i would like to visit a quiet temple in kyoto after lunch",
                generated.single().getValue("normalizedSourceText").jsonPrimitive.content)
            assertEquals(1, commands.size)
            val prompt = commands.single().messages.single().content
            assertTrue(prompt.contains("learningLanguage (en)"))
            assertTrue(prompt.contains("originLanguage ($origin)"))
        }
    }

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
