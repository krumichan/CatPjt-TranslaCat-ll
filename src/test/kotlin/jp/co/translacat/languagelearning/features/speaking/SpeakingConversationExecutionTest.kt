package jp.co.translacat.languagelearning.features.speaking

import jp.co.translacat.languagelearning.features.speaking.execution.*
import jp.co.translacat.languagelearning.shared.ai.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SpeakingConversationExecutionTest {
    @Test
    fun `원본 Python 대화 도움말 흐름의 prompt schema 결과 실패 호출수를 보존한다`() = runBlocking {
        // 준비: 기존 Python 서비스를 직접 실행한 결과를 읽는다. 모델 응답만 고정한다.
        val cases = Json.parseToJsonElement(
            checkNotNull(
                javaClass.getResource(
                    "/contracts/speaking-conversation-python-golden.json",
                ),
            ).readText(),
        ).jsonArray
        for (entry in cases) {
            val case = entry.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val kind = case.getValue("kind").jsonPrimitive.content
            val commands = mutableListOf<ModelExecutionCommand>()
            val model = ModelExecutionPort { command ->
                commands += command
                val error = case["providerFailure"]?.jsonPrimitive?.contentOrNull
                if (error != null) {
                    val status = if (error == "SAFETY_400") 400 else error.toIntOrNull()
                    val signal = when (error) {
                        "RATE_SIGNAL" -> "RATE_LIMIT"
                        "DEADLINE_SIGNAL" -> "DEADLINE"
                        "SAFETY_SIGNAL", "SAFETY_400" -> "SAFETY"
                        else -> null
                    }
                    val code = when (error) {
                        "REFUSAL" -> "REFUSAL"
                        "TIMEOUT", "SDK_TIMEOUT" -> "PROVIDER_TIMEOUT"
                        else -> "PROVIDER_UNAVAILABLE"
                    }
                    throw ModelExecutionFailure(
                        code, 502, false,
                        failureKind = if (status != null) "HTTP_STATUS" else
                            error.takeUnless { it == "REFUSAL" || it.endsWith("_SIGNAL") },
                        providerStatus = status, failureSignal = signal,
                    )
                }
                ModelExecutionResult(case.getValue("output"), 7, 2, "synthetic", "fixed")
            }
            val conversation = SpeakingConversationExecution(model, nanoTime = { 0L })
            val assistance = SpeakingAssistanceExecution(model, nanoTime = { 0L })
            suspend fun generate(): JsonObject = if (kind == "conversation")
                conversation.generate(case.getValue("rawRequest").jsonObject)
            else assistance.generate(case.getValue("rawRequest").jsonObject)

            // 실행 및 검증: 실패를 성공으로 보정하지 않으며 성공 중복만 캐시한다.
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
                assertEquals(case.getValue("response"), generate(), name)
                assertEquals(case.getValue("replay"), generate(), name)
            }
            assertEquals(case.getValue("calls").jsonPrimitive.int, commands.size, name)
            commands.forEach {
                assertEquals(case.getValue("prompt").jsonPrimitive.content, it.messages.single().content, name)
                assertEquals(ModelTier.LUNA, it.tier, name)
                assertEquals(if (kind == "conversation") 4096 else 2048, it.maxOutputTokens, name)
                assertEquals(SpeakingConversationContract.schema(kind, "Payload"), it.responseSchema, name)
                assertEquals(SpeakingConversationContract.instructions(kind), it.instructions, name)
                assertFalse(it.strict, name)
            }
        }
    }

    @Test
    fun `동시 같은 키만 합치고 취소는 다른 키와 이후 재시도를 막지 않는다`() = runBlocking {
        // 준비: 실제 coroutine 병렬 호출을 하나의 Provider 대기 지점에서 겹치게 한다.
        val cache = SpeakingExecutionCache()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val body = buildJsonObject { put("synthetic", true) }
        val first = async { cache.execute("same") { calls++; entered.complete(Unit); release.await(); body } }
        entered.await()
        val second = async { cache.execute("same") { calls++; body } }

        // 실행: 다른 키는 먼저 끝나며 같은 키는 성공 결과 하나를 공유한다.
        assertEquals(body to false, cache.execute("other") { body })
        release.complete(Unit)
        assertEquals(body to false, first.await())
        assertEquals(body to true, second.await())
        assertEquals(1, calls)

        // 검증: 취소된 키는 성공으로 저장하지 않고 다음 실행에서 재사용할 수 있다.
        val cancelled = launch { cache.execute("cancel") { throw CancellationException("synthetic") } }
        cancelled.join()
        assertEquals(body to false, cache.execute("cancel") { body })
    }
}
