package jp.co.translacat.languagelearning.features.speaking

import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingCoachingExecution
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingEvaluationExecution
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingStageFailure
import jp.co.translacat.languagelearning.shared.ai.HttpModelExecution
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionPort
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpeakingEvaluationHttpIntegrationTest {
    @Test
    fun `실제 Python 범용 모델 HTTP로 원본 평가 코칭67개 흐름을 검증한다`() = runBlocking {
        // 준비: 업무 경로는 Kotlin에 두며 모델 출력·SDK 예외만 Python 테스트 Provider로 고정한다.
        val cases = Json.parseToJsonElement(
            checkNotNull(
                javaClass.getResource(
                    "/contracts/speaking-evaluation-python-golden.json",
                ),
            ).readText(),
        ).jsonArray
        HttpModelExecution(checkNotNull(System.getenv("LL_TEST_AI_URL")), "synthetic-local-model-key").use { http ->
            for (entry in cases) {
                val case = entry.jsonObject
                val name = case.getValue("name").jsonPrimitive.content
                val sessionId = JsonPrimitive("speaking-evaluation-http-$name")
                val request = JsonObject(case.getValue("rawRequest").jsonObject + ("sessionId" to sessionId))
                var calls = 0
                val counted = ModelExecutionPort { calls++; http.execute(it) }
                val evaluation = SpeakingEvaluationExecution(counted, nanoTime = { 0L })
                val coaching = SpeakingCoachingExecution(counted, nanoTime = { 0L })
                suspend fun execute() = if (case.getValue("kind") == JsonPrimitive("evaluation"))
                    evaluation.evaluate(request) else coaching.coach(request)

                // 실행 및 검증: actual HTTP 분류·파싱을 통과한 결과와 호출 수가 원본에 일치해야 한다.
                if ("failure" in case) {
                    val failure = assertFailsWith<SpeakingStageFailure>(name) { execute() }
                    assertEquals(
                        case.getValue("failure"),
                        buildJsonObject {
                            put("code", failure.code); put("stage", failure.stage); put("retryable", failure.retryable)
                        },
                        name,
                    )
                } else {
                    val expected = JsonObject(case.getValue("response").jsonObject + ("sessionId" to sessionId))
                    assertEquals(expected, execute(), name)
                    assertEquals(expected, execute(), name)
                }
                assertEquals(case.getValue("calls").jsonPrimitive.int, calls, name)
            }
        }
    }
}
