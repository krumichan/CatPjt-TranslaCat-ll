package jp.co.translacat.languagelearning.features.speaking

import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingCoachingExecution
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingEvaluationContract
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingEvaluationExecution
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingStageFailure
import jp.co.translacat.languagelearning.shared.ai.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SpeakingEvaluationExecutionTest {
    @Test
    fun `평가와 코칭 원본67개 결과 프롬프트 동적Schema 실패 재시도를 보존한다`() = runBlocking {
        // 준비: 원본 Python 업무를 합성 Provider로 직접 실행한 자료를 사용한다.
        val cases = Json.parseToJsonElement(
            checkNotNull(
                javaClass.getResource(
                    "/contracts/speaking-evaluation-python-golden.json",
                ),
            ).readText(),
        ).jsonArray
        for (entry in cases) {
            val case = entry.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val kind = case.getValue("kind").jsonPrimitive.content
            val commands = mutableListOf<ModelExecutionCommand>()
            val provider = ModelExecutionPort { command ->
                commands += command
                case["providerFailure"]?.jsonPrimitive?.contentOrNull?.let { throw evaluationFixtureFailure(it) }
                ModelExecutionResult(case.getValue("output"), 7, 2, "synthetic", "fixed")
            }
            val evaluation = SpeakingEvaluationExecution(provider, nanoTime = { 0L })
            val coaching = SpeakingCoachingExecution(provider, nanoTime = { 0L })
            val raw = case.getValue("rawRequest").jsonObject
            suspend fun execute(request: JsonObject = raw): JsonObject = if (kind == "evaluation")
                evaluation.evaluate(request) else coaching.coach(request)

            // 실행: 새 requestId의 동일 업무 요청은 기존 성공 결과를 재사용한다.
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
                assertEquals(case.getValue("response"), execute(), name)
                assertEquals(
                    case.getValue("replay"),
                    execute(
                        JsonObject(
                            raw +
                                ("requestId" to JsonPrimitive("synthetic-replay")),
                        ),
                    ),
                    name,
                )
            }

            // 검증: 모델 출력뿐 아니라 전체 동적 Schema와 프롬프트·예산·호출 수를 대조한다.
            assertEquals(case.getValue("calls").jsonPrimitive.int, commands.size, name)
            commands.forEach {
                assertEquals(
                    case.getValue("prompt").jsonPrimitive.content, it.messages.single().content, "$name prompt",
                )
                assertEquals<JsonElement?>(case.getValue("schema"), it.responseSchema, "$name schema")
                assertEquals(SpeakingEvaluationContract.instructions(kind), it.instructions, name)
                assertEquals(ModelTier.MINI, it.tier, name)
                assertEquals(if (kind == "evaluation") 8192 else 4096, it.maxOutputTokens, name)
                assertFalse(it.strict, name)
            }
        }
    }

    @Test
    fun `새 모델 protocol 실패는 기존 점수 또는 모델 실패로 숨기지 않는다`() = runBlocking {
        // 준비
        val raw = Json.parseToJsonElement(
            checkNotNull(
                javaClass.getResource(
                    "/contracts/speaking-evaluation-python-golden.json",
                ),
            ).readText(),
        ).jsonArray.first().jsonObject
            .getValue("rawRequest").jsonObject
        var calls = 0
        val provider = ModelExecutionPort {
            calls++
            throw ModelExecutionFailure("MODEL_RESPONSE_INVALID", 502, false)
        }

        // 실행
        val failure = assertFailsWith<ModelExecutionFailure> { SpeakingEvaluationExecution(provider).evaluate(raw) }

        // 검증
        assertEquals("MODEL_RESPONSE_INVALID", failure.code)
        assertEquals(1, calls)
    }
}

private fun evaluationFixtureFailure(error: String): ModelExecutionFailure {
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
    return ModelExecutionFailure(
        code, 502, false,
        failureKind = if (status != null) "HTTP_STATUS" else error.takeUnless {
            it == "REFUSAL" || it.endsWith(
                "_SIGNAL",
            )
        },
        providerStatus = status, failureSignal = signal,
    )
}
