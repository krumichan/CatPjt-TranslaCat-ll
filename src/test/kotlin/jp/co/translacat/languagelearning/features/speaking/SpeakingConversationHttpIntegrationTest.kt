package jp.co.translacat.languagelearning.features.speaking

import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingAssistanceExecution
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingConversationExecution
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingStageFailure
import jp.co.translacat.languagelearning.shared.ai.HttpModelExecution
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionPort
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpeakingConversationHttpIntegrationTest {
    @Test
    fun `실제 Python HTTP를 통해 대화 도움말 원본 정상 실패 사례를 대조한다`() = runBlocking {
        // 준비: 실제 인증·Schema·HTTP adapter를 사용하며 출력만 테스트 Provider로 고정한다.
        val cases = Json.parseToJsonElement(
            checkNotNull(
                javaClass.getResource(
                    "/contracts/speaking-conversation-python-golden.json",
                ),
            ).readText(),
        ).jsonArray
        HttpModelExecution(checkNotNull(System.getenv("LL_TEST_AI_URL")), "synthetic-local-model-key").use { http ->
            for (entry in cases) {
                val case = entry.jsonObject
                val name = case.getValue("name").jsonPrimitive.content
                val id = JsonPrimitive("speaking-http-$name")
                val request = JsonObject(case.getValue("rawRequest").jsonObject + ("requestId" to id))
                var calls = 0
                val counted = ModelExecutionPort { calls++; http.execute(it) }
                val conversation = SpeakingConversationExecution(counted, nanoTime = { 0L })
                val assistance = SpeakingAssistanceExecution(counted, nanoTime = { 0L })
                suspend fun generate() = if (case.getValue("kind") == JsonPrimitive("conversation"))
                    conversation.generate(request) else assistance.generate(request)

                // 실행 및 검증: 서버가 분류한 SDK 출처를 LL 기존 정책이 해석하며 실제 호출 수도 같아야 한다.
                if ("failure" in case) {
                    val failure = assertFailsWith<SpeakingStageFailure>(name) { generate() }
                    assertEquals(
                        case.getValue("failure"),
                        buildJsonObject {
                            put("code", failure.code); put("stage", failure.stage); put("retryable", failure.retryable)
                        },
                        name,
                    )
                } else {
                    assertEquals(
                        JsonObject(case.getValue("response").jsonObject + ("requestId" to id)), generate(), name,
                    )
                    assertEquals(JsonObject(case.getValue("replay").jsonObject + ("requestId" to id)), generate(), name)
                }
                assertEquals(case.getValue("calls").jsonPrimitive.int, calls, name)
            }
        }
    }
}
