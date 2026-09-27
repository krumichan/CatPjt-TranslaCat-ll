package jp.co.translacat.languagelearning.features.speaking.execution

import jp.co.translacat.languagelearning.shared.ai.*
import jp.co.translacat.languagelearning.shared.schema.PydanticSchemaFailure
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant

internal class SpeakingConversationExecution(
    model: ModelExecutionPort,
    clock: Clock = Clock.systemUTC(),
    nanoTime: () -> Long = System::nanoTime,
) {
    private val execution = SpeakingConversationModelExecution("conversation", model, clock, nanoTime)
    suspend fun generate(request: JsonObject, deadlineUtc: Instant? = null): JsonObject =
        execution.generate(request, deadlineUtc)
}

internal class SpeakingAssistanceExecution(
    model: ModelExecutionPort,
    clock: Clock = Clock.systemUTC(),
    nanoTime: () -> Long = System::nanoTime,
) {
    private val execution = SpeakingConversationModelExecution("assistance", model, clock, nanoTime)
    suspend fun generate(request: JsonObject, deadlineUtc: Instant? = null): JsonObject =
        execution.generate(request, deadlineUtc)
}

private class SpeakingConversationModelExecution(
    private val kind: String,
    private val model: ModelExecutionPort,
    private val clock: Clock,
    private val nanoTime: () -> Long,
) {
    private val cache = SpeakingExecutionCache(nanoTime)
    private val task = "LANGUAGE_LEARNING_SPEAKING_${kind.uppercase()}"

    suspend fun generate(raw: JsonObject, deadlineUtc: Instant?): JsonObject {
        val request = SpeakingConversationContract.request(kind, raw)
        if (kind == "assistance" && request.string("assistanceType") !in
            setOf("HINT", "TRANSLATION", "SAMPLE_ANSWER")
        )
            throw SpeakingStageFailure("INVALID_RESPONSE_SCHEMA", "CONVERSATION", false)
        val retries = if (kind == "conversation")
            minOf(2, request.getValue("sessionPolicySnapshot").jsonObject.integer("automaticRetryLimitPerStage"))
        else 2
        val deadline = minOf(deadlineUtc ?: Instant.MAX, clock.instant().plusSeconds((retries + 1) * 30L))
        val (result, replay) = cache.execute(request.string("idempotencyKey")) {
            generateOnce(request, retries, deadline)
        }
        return if (kind == "assistance") JsonObject(result + ("idempotentReplay" to JsonPrimitive(replay))) else result
    }

    private suspend fun generateOnce(request: JsonObject, retries: Int, deadline: Instant): JsonObject {
        // 하나의 업무 deadline 안에서 기존 단계별 최대30초와 즉시 재시도 상한을 지킨다.
        val started = nanoTime()
        val prompt = SpeakingConversationContract.prompt(kind, request)
        val (result, payload) = speakingStageRetry(retries, "CONVERSATION", deadline, clock) {
            val result = try {
                model.execute(
                    ModelExecutionCommand(
                        traceId = request.string("requestId"),
                        instructions = SpeakingConversationContract.instructions(kind),
                        messages = listOf(ModelMessage("user", prompt)), tier = ModelTier.LUNA,
                        maxOutputTokens = if (kind == "conversation") 4096 else 2048,
                        deadlineUtc = minOf(deadline, clock.instant().plusSeconds(30)),
                        responseSchema = SpeakingConversationContract.schema(kind, "Payload"),
                        schemaName = task, strict = false, taskName = task,
                    ),
                )
            } catch (failure: ModelExecutionFailure) {
                throw speakingProviderFailure(failure, "CONVERSATION", "CONVERSATION_GENERATION_FAILED")
            }

            // 구조 오류와 교차 필드 오류는 원본처럼 실패로 판정하고 응답을 임의로 보정하지 않는다.
            val payload = try {
                SpeakingConversationContract.payload(kind, request, result.output)
            } catch (_: PydanticSchemaFailure) {
                throw SpeakingStageFailure("INVALID_RESPONSE_SCHEMA", "CONVERSATION", true)
            } catch (_: IllegalArgumentException) {
                throw SpeakingStageFailure("INVALID_RESPONSE_SCHEMA", "CONVERSATION", true)
            }
            result to payload
        }
        val elapsed = ((nanoTime() - started) / 1_000_000).coerceAtLeast(0)
        return buildJsonObject {
            listOf("requestId", "sessionId", "turnIndex").forEach { put(it, request.getValue(it)) }
            if (kind == "conversation") {
                put("assistantText", payload.getValue("assistantText"))
                put("conversation", conversation(request, payload))
            } else {
                put("type", payload.getValue("type")); put("content", payload.getValue("content"))
                put("idempotentReplay", false)
            }
            put(
                "usage",
                buildJsonObject {
                    put("stt", JsonNull); put("tts", JsonNull); put("evaluation", JsonNull)
                    put(
                        "conversation",
                        buildJsonObject {
                            put("latencyMs", elapsed); put("inputTokens", result.inputTokens); put(
                            "outputTokens", result.outputTokens,
                        )
                            put("audioSeconds", 0.0); put("ttsCharacters", 0); put("ttsAudioSeconds", 0.0)
                            put("provider", result.provider); put("model", result.model)
                            put("promptVersion", "speaking-conversation-v2"); put("evaluationVersion", JsonNull)
                        },
                    )
                },
            )
        }
    }

    private fun conversation(request: JsonObject, payload: JsonObject): JsonObject {
        val policy = request.getValue("sessionPolicySnapshot").jsonObject
        var shouldEnd = payload.boolean("shouldEnd")
        var reason = payload.getValue("endReason")
        if (!shouldEnd) {
            // 모델이 명시한 종료를 우선하며 그 외에는 기존 최대 turn·시간 순서로 판정한다.
            if (request.integer("turnIndex") >= policy.integer("maxTurns")) {
                shouldEnd = true; reason = JsonPrimitive("MAX_TURNS")
            } else if (request.getValue("sessionElapsedSeconds").jsonPrimitive.double >= policy.integer(
                    "maxSessionMinutes",
                ) * 60
            ) {
                shouldEnd = true; reason = JsonPrimitive("MAX_SESSION_DURATION")
            }
        }
        return JsonObject(
            payload.filterKeys { it != "assistantText" } + mapOf(
                "shouldEnd" to JsonPrimitive(shouldEnd), "endReason" to reason,
                "assistanceLevel" to JsonPrimitive(SpeakingConversationContract.assistanceLevel(request)),
            ),
        )
    }
}
