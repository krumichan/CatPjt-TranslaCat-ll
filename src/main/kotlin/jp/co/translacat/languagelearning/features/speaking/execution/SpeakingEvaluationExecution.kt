package jp.co.translacat.languagelearning.features.speaking.execution

import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingEligibility
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingMetricState
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingPolicy
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingPracticeMode
import jp.co.translacat.languagelearning.shared.ai.*
import jp.co.translacat.languagelearning.shared.schema.PydanticSchemaFailure
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

internal class SpeakingEvaluationExecution(
    model: ModelExecutionPort,
    clock: Clock = Clock.systemUTC(),
    nanoTime: () -> Long = System::nanoTime,
) {
    private val execution = SpeakingResultModelExecution("evaluation", model, clock, nanoTime)
    suspend fun evaluate(request: JsonObject, deadlineUtc: Instant? = null): JsonObject =
        execution.execute(request, deadlineUtc)
}

internal class SpeakingCoachingExecution(
    model: ModelExecutionPort,
    clock: Clock = Clock.systemUTC(),
    nanoTime: () -> Long = System::nanoTime,
) {
    private val execution = SpeakingResultModelExecution("coaching", model, clock, nanoTime)
    suspend fun coach(request: JsonObject, deadlineUtc: Instant? = null): JsonObject =
        execution.execute(request, deadlineUtc)
}

private class SpeakingResultModelExecution(
    private val kind: String,
    private val model: ModelExecutionPort,
    private val clock: Clock,
    private val nanoTime: () -> Long,
) {
    private val cache = SpeakingExecutionCache(nanoTime)
    private val responseJson = Json { encodeDefaults = true }
    private val task = if (kind == "evaluation") "LANGUAGE_LEARNING_SPEAKING_EVALUATION"
    else "LANGUAGE_LEARNING_SPEAKING_SESSION_COACHING"
    private val promptVersion = if (kind == "evaluation") SpeakingPolicy.EVALUATION_PROMPT_VERSION
    else "speaking-session-coaching-prompt-v1"
    private val version = if (kind == "evaluation") SpeakingPolicy.EVALUATION_VERSION
    else "speaking-session-coaching-schema-v1"

    suspend fun execute(raw: JsonObject, deadlineUtc: Instant?): JsonObject {
        val request = SpeakingEvaluationContract.request(kind, raw)
        if (kind == "coaching" && request.string("resultPolicyVersion") != "free-session-coaching-v1")
            throw SpeakingStageFailure("INVALID_RESPONSE_SCHEMA", "EVALUATION", false)
        if (kind == "evaluation" && request.integer("manualRetryAttempt") > 1)
            throw SpeakingStageFailure("MANUAL_RETRY_LIMIT_EXCEEDED", "EVALUATION", false)

        // 기존 성공 캐시 키를 보존한다. 실패는 저장하지 않고 재조회 응답의 requestId만 갱신한다.
        val key = if (kind == "evaluation") listOf("sessionId", "evaluationScope", "evaluationPolicyVersion")
        else listOf("sessionId", "resultPolicyVersion", "sourceSnapshotHash")
        val deadline = minOf(deadlineUtc ?: Instant.MAX, clock.instant().plusSeconds(180))
        val (response, _) = cache.execute(key.joinToString(":") { request.string(it) }) {
            once(request, deadline)
        }
        return JsonObject(response + ("requestId" to request.getValue("requestId")))
    }

    private suspend fun once(request: JsonObject, deadline: Instant): JsonObject {
        val eligibility = if (kind == "evaluation") eligibility(request) else null
        if (eligibility != null && !eligibility.eligibleBeforeAi)
            return evaluationResponse(request, eligibility, null, null)
        if (kind == "coaching" && request.objects("userTurns").none(SpeakingEvaluationContract::usable))
            return coachingResponse(
                request,
                buildJsonObject {
                    put("contentStatus", "NO_USABLE_EVIDENCE")
                    put("limitationReasons", strings(listOf("NO_USABLE_LEARNER_TRANSCRIPT")))
                    put("items", JsonArray(emptyList()))
                },
                null,
            )

        // 사전 판정이 허용한 요청만 기존 MINI/60초/최대3회로 실행한다.
        val started = nanoTime()
        val prompt = SpeakingEvaluationPrompt.build(kind, request)
        val schema = SpeakingEvaluationContract.providerSchema(kind, request)
        val (result, payload) = speakingStageRetry(2, "EVALUATION", deadline, clock) {
            val result = try {
                model.execute(
                    ModelExecutionCommand(
                        traceId = request.string("requestId"),
                        instructions = SpeakingEvaluationContract.instructions(kind),
                        messages = listOf(ModelMessage("user", prompt)), tier = ModelTier.MINI,
                        maxOutputTokens = if (kind == "evaluation") 8192 else 4096,
                        deadlineUtc = minOf(deadline, clock.instant().plusSeconds(60)),
                        responseSchema = schema, schemaName = task, strict = false, taskName = task,
                    ),
                )
            } catch (failure: ModelExecutionFailure) {
                throw speakingProviderFailure(failure, "EVALUATION", "EVALUATION_FAILED")
            }

            // 구조·증거·평가 가능 축 검증의 실패는 원본처럼 재시도 가능한 업무 실패로 보존한다.
            val payload = try {
                SpeakingEvaluationContract.payload(kind, request, result.output)
            } catch (_: PydanticSchemaFailure) {
                throw SpeakingStageFailure("INVALID_RESPONSE_SCHEMA", "EVALUATION", true)
            } catch (_: IllegalArgumentException) {
                throw SpeakingStageFailure("INVALID_RESPONSE_SCHEMA", "EVALUATION", true)
            }
            result to payload
        }
        val usage = buildJsonObject {
            put("latencyMs", ((nanoTime() - started) / 1_000_000).coerceAtLeast(0))
            put("inputTokens", result.inputTokens); put("outputTokens", result.outputTokens)
            put("audioSeconds", 0.0); put("ttsCharacters", 0); put("ttsAudioSeconds", 0.0)
            put("provider", result.provider); put("model", result.model)
            put("promptVersion", promptVersion); put("evaluationVersion", version)
        }
        return if (kind == "evaluation") evaluationResponse(request, checkNotNull(eligibility), payload, usage)
        else coachingResponse(request, payload, usage)
    }

    private fun eligibility(request: JsonObject): SpeakingEligibility {
        val readAloud = SpeakingEvaluationContract.mode(request) == SpeakingPracticeMode.READ_ALOUD
        val problem = request.string("evaluationScope") == "READ_ALOUD_PROBLEM"
        return SpeakingPolicy.eligibility(
            request.objects("userTurns").map(SpeakingEvaluationContract::turn),
            requiredUserTurns = if (problem) 2 else if (readAloud) 10 else 5,
            requiredSpeechSeconds = if (problem || readAloud) 0.0 else 60.0,
        )
    }

    private fun evaluationResponse(
        request: JsonObject, eligibility: SpeakingEligibility,
        payload: JsonObject?, stageUsage: JsonObject?,
    ): JsonObject {
        // 전체 confidence와 원본 가중치를 적용하되 점수가 없는 평가를 완료 점수로 표시하지 않는다.
        val confidence = payload?.number("evaluationConfidence")
        val metrics = payload?.let(SpeakingEvaluationContract::metricValues).orEmpty()
        val overall = if (confidence != null && confidence >= .7) SpeakingPolicy.overall(metrics) else null
        val evaluated = overall != null
        val axes = metrics.filter { it.state == SpeakingMetricState.EVALUATED }.map { it.type }
        return buildJsonObject {
            put("requestId", request.getValue("requestId")); put("sessionId", request.getValue("sessionId"))
            put("status", if (evaluated) "EVALUATED" else "INSUFFICIENT_EVIDENCE")
            put("overallScore", overall); put("evaluationConfidence", confidence)
            listOf("metrics", "strengths", "improvements", "recommendedExpressions", "pronunciationPractice").forEach {
                put(it, payload?.getValue(it) ?: JsonArray(emptyList()))
            }
            put("profileSignals", if (evaluated) payload!!.getValue("profileSignals") else JsonArray(emptyList()))
            put("eligibility", responseJson.encodeToJsonElement(SpeakingEligibility.serializer(), eligibility))
            put("evaluationVersion", version); put("scoringPolicyVersion", SpeakingPolicy.SCORING_VERSION)
            put("promptVersion", promptVersion); put("usage", usage(stageUsage))
            put("evaluatedAxes", strings(axes.map { it.name }))
            put(
                "evaluationCoverage",
                kotlin.math.round(axes.sumOf { SpeakingPolicy.weights.getValue(it) } * 10000) / 10000,
            )
            put("evidencePolicyVersion", SpeakingPolicy.EVIDENCE_VERSION); put(
            "evidenceSource", SpeakingPolicy.EVIDENCE_SOURCE,
        )
        }
    }

    private fun coachingResponse(request: JsonObject, payload: JsonObject, stageUsage: JsonObject?): JsonObject {
        val turns =
            request.objects("userTurns").filter(SpeakingEvaluationContract::usable).associateBy { it.string("turnId") }
        val references = SpeakingEvaluationContract.references(request)
        return buildJsonObject {
            put("requestId", request.getValue("requestId")); put("sessionId", request.getValue("sessionId"))
            put("resultKind", "SESSION_COACHING"); put("resultPolicyVersion", request.getValue("resultPolicyVersion"))
            put("schemaVersion", version); put("sourceSnapshotHash", request.getValue("sourceSnapshotHash"))
            put("contentStatus", payload.getValue("contentStatus")); put(
            "limitationReasons", payload.getValue("limitationReasons"),
        )

            // 인용·revision·원본 전사 hash는 모델 출력에서 받지 않고 검증된 불변 요청에서 결합한다.
            put(
                "items",
                JsonArray(
                    payload.objects("items").map { item ->
                        val turn = turns.getValue(item.string("turnId"))
                        buildJsonObject {
                            put("observationId", item.getValue("observationId")); put("kind", item.getValue("kind"))
                            put(
                                "evidence",
                                buildJsonObject {
                                    put("turnId", turn.getValue("turnId")); put("turnIndex", turn.getValue("turnIndex"))
                                    put("recordingRevision", turn.getValue("recordingRevision"))
                                    put("transcriptExcerpt", item.getValue("sourceExcerpt"))
                                    put(
                                        "transcriptHash",
                                        MessageDigest.getInstance("SHA-256")
                                            .digest(turn.string("transcript").toByteArray(Charsets.UTF_8))
                                            .joinToString("") { "%02x".format(it) },
                                    )
                                    put("referenceAssistantTurnId", references[turn.string("turnId")])
                                    put("assistanceUsage", turn.getValue("assistanceUsage"))
                                    put("sourceProvenance", "AUTOMATIC_SPEECH_RECOGNITION"); put(
                                    "verbatimAccuracyVerified", false,
                                )
                                },
                            )
                            put("message", item.getValue("message")); put(
                            "suggestedExpression", item.getValue("suggestedExpression"),
                        )
                            put("suggestionIsLearnerEvidence", false)
                        }
                    },
                ),
            )
            put("promptVersion", promptVersion); put("usage", usage(stageUsage))
        }
    }

    private fun usage(stageUsage: JsonObject?): JsonObject = buildJsonObject {
        put("stt", JsonNull); put("conversation", JsonNull); put("tts", JsonNull)
        put("evaluation", if (kind == "evaluation") stageUsage ?: JsonNull else JsonNull)
        if (kind == "coaching" && stageUsage != null) put("coaching", stageUsage)
    }
}
