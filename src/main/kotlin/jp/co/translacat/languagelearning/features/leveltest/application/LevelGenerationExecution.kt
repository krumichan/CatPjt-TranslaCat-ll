package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelQuestionData
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelReferenceAudio
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelTestItemType
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.*
import jp.co.translacat.languagelearning.shared.ai.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

/** 기존 3회 생성과 후보별 검증·보정·다양성 정책을 LL에서 실행한다. */
internal class LevelGenerationExecution(
    private val model: ModelExecutionPort,
    private val speech: SpeechExecutionPort,
    private val publishAudio: suspend (LevelAudioUpload, ByteArray, String) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val designs = LevelVocabDesignExecution(model, clock)
    private val verifier = LevelGenerationVerificationExecution(model, clock)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun generate(rawRequest: JsonObject, upload: LevelAudioUpload?, deadline: Instant): LevelQuestionData {
        // HTTP DTO 대신 같은 Pydantic 계약으로 문맥을 정규화하여 기본값·필드 순서와 프롬프트를 보존한다.
        val request = try {
            LevelPydanticSchema.decode(rawRequest, LevelGenerationAssets.baseSchema("generation-request"))
        } catch (_: LevelSchemaFailure) {
            throw LevelTestException("LEVEL_TEST_GENERATION_REQUEST_INVALID", 422, "Level Test 생성 요청 계약이 유효하지 않습니다.")
        }
        val slot = LevelTestRules.slot(request.getValue("questionNumber").jsonPrimitive.int)
        if (request["domain"] != JsonPrimitive(slot.domain.name) || request["itemType"] != JsonPrimitive(
                slot.itemType.name,
            )
        )
            throw LevelTestException(
                "LEVEL_TEST_GENERATION_REQUEST_INVALID", 422, "Level Test Recipe와 요청 Domain/ItemType이 일치하지 않습니다.",
            )
        val key = request.getValue("requestId").jsonPrimitive.content
        val origin = request.getValue("originLanguage").jsonPrimitive.content
        val learning = request.getValue("learningLanguage").jsonPrimitive.content
        val kind = request.getValue("itemType").jsonPrimitive.content
        val context = request.getValue("diversityContext").jsonObject
        val rejected = mutableListOf<String>()
        val valid = mutableListOf<Pair<JsonObject, Map<String, Int>>>()
        val totals = GenerationTotals()
        var contentRejections = 0
        var schemaRejections = 0
        var provider: String? = null
        var modelName: String? = null

        for (attempt in 0..2) {
            // 어휘 설계 실패는 기존 direct generation으로 넘어가되 독립 검증을 그대로 수행한다.
            val approved = if (kind == "VOCAB_CONTEXT_CHOICE") {
                totals.increment("designCalls")
                val stage = designs.plan(request, attempt, rejected, deadline)
                totals.add(stage.inputTokens, stage.outputTokens, stage.latencyMs)
                if (stage.value == null) totals.increment("designFallbacks")
                stage.value
            } else null
            val started = clock.millis()
            totals.increment("generationCalls")
            val result = try {
                model.execute(
                    ModelExecutionCommand(
                        key, LevelGenerationAssets.instructions("generation"),
                        listOf(
                            ModelMessage(
                                "user",
                                LevelGenerationAssets.prompt(
                                    request, attempt, rejected.takeLast(12), approved?.let(::JsonArray),
                                ),
                            ),
                        ),
                        ModelTier.LUNA, 8192, minOf(deadline, clock.instant().plusSeconds(30)),
                        LevelGenerationAssets.schema(request, !approved.isNullOrEmpty()),
                        "LevelTestQuestionGenerationPayload",
                        taskName = "LANGUAGE_LEARNING_LEVEL_TEST_GENERATION",
                    ),
                )
            } catch (failure: ModelExecutionFailure) {
                // 호출별 timeout과 전체 deadline을 구분하고 기존 생성 3회 한도를 넘기지 않는다.
                if (attempt < 2 && LevelModelFailurePolicy.retryGeneration(
                        failure,
                    ) && clock.instant() < deadline
                ) continue
                throw LevelTestException(
                    failure.code, LevelModelFailurePolicy.status(failure), "Level Test 문제 생성 Provider 호출에 실패했습니다.",
                )
            }
            totals.add(result.inputTokens, result.outputTokens, elapsed(started))
            provider = result.provider
            modelName = result.model
            val output = result.output as? JsonObject ?: throw LevelTestException(
                "LEVEL_TEST_GENERATOR_SCHEMA_INVALID", 502,
                "Level Test 문제 생성 응답 Schema가 유효하지 않습니다.",
            )
            val normalized = LevelGenerationNormalizer.normalize(output, origin, learning)
            val batch = LevelGenerationCandidateParser.batch(normalized.output)
            schemaRejections += batch.rejected
            totals.increment("schemaRejections", batch.rejected)
            rejected += batch.reasons

            // 잘못된 형제 후보는 독립 폐기하며 정상 후보의 기존 판정 순서를 유지한다.
            for ((_, original) in batch.candidates) {
                totals.increment("candidatesSeen")
                var candidate = original
                var scores = emptyMap<String, Int>()
                var reason = LevelGenerationCandidatePolicy.rejection(request, candidate)
                var design: JsonObject? = null
                var semanticFailure = false
                if (reason == null && !approved.isNullOrEmpty()) {
                    val binding = designs.binding(candidate, approved)
                    design = binding.first
                    reason = binding.second
                }
                val selection = selection(candidate)
                if (reason == null && selection != null) {
                    val verifyStarted = clock.millis()
                    var verification = verifier.choice(key, origin, learning, candidate, deadline)
                    totals.add(verification.inputTokens, verification.outputTokens, elapsed(verifyStarted))
                    totals.increment("verifierCalls", verification.calls)
                    reason = verification.reason
                    scores = verification.optionScores
                    if (reason != null) {
                        semanticFailure = true
                        totals.increment("semanticRejections")
                        if (verification.verdict?.get("verifiable") == JsonPrimitive(false)) totals.increment(
                            "semanticUnverifiable",
                        )
                        val verdict = verification.verdict
                        if (kind == "VOCAB_CONTEXT_CHOICE" && design != null && verdict != null &&
                            LevelGenerationVerificationPolicy.repairable(candidate, verdict, selection)
                        ) {
                            totals.increment("repairCalls")
                            val repaired = designs.repair(request, candidate, design, verdict, selection, deadline)
                            totals.add(repaired.inputTokens, repaired.outputTokens, repaired.latencyMs)
                            repaired.value?.let { corrected ->
                                reason = LevelGenerationCandidatePolicy.rejection(request, corrected)
                                    ?: designs.binding(corrected, approved.orEmpty()).second
                                if (reason == null) {
                                    val repairStarted = clock.millis()
                                    verification = verifier.choice(key, origin, learning, corrected, deadline)
                                    totals.add(
                                        verification.inputTokens, verification.outputTokens, elapsed(repairStarted),
                                    )
                                    totals.increment("verifierCalls", verification.calls)
                                    reason = verification.reason
                                    if (reason == null) {
                                        candidate = corrected
                                        scores = verification.optionScores
                                        totals.increment("repairAccepted")
                                    } else {
                                        totals.increment("semanticRejections")
                                        if (verification.verdict?.get("verifiable") == JsonPrimitive(
                                                false,
                                            )
                                        ) totals.increment("semanticUnverifiable")
                                    }
                                }
                            }
                        }
                    }
                }
                if (reason == null && kind in LevelGenerationAssets.guided) {
                    val taskStarted = clock.millis()
                    val task = verifier.task(key, origin, learning, candidate, deadline)
                    totals.add(task.inputTokens, task.outputTokens, elapsed(taskStarted))
                    totals.increment("taskVerifierCalls")
                    reason = task.reason
                    if (reason != null) {
                        semanticFailure = true; totals.increment("taskRejections")
                    }
                }
                if (reason != null) {
                    contentRejections++
                    if (!semanticFailure) totals.increment("deterministicRejections")
                    rejected += checkNotNull(reason)
                    continue
                }

                // 의미 검증을 통과한 후보만 다양성 완화 후보로 보관한다. protocol 실패는 위에서 즉시 전파된다.
                valid += candidate to scores
                val decision = LevelGenerationDiversity(context).validate(candidate)
                totals.record(decision)
                if (decision.accepted) return response(
                    request, candidate, scores, decision.metadata, false, totals,
                    provider, modelName, upload, deadline,
                )
                rejected += candidate.getValue("diversityMetadata").jsonObject.getValue(
                    "semanticSummary",
                ).jsonPrimitive.content
            }
        }

        // 기존 fallback은 과거 이력 유사도만 완화하며 후보 재생성·재검증 호출을 추가하지 않는다.
        for ((candidate, scores) in valid) {
            val decision = LevelGenerationDiversity(context, relaxedHistory = true).validate(candidate)
            if (decision.accepted) return response(
                request, candidate, scores, decision.metadata, true, totals,
                provider, modelName, upload, deadline,
            )
        }
        val code =
            if (valid.isEmpty() && (contentRejections > 0 || schemaRejections > 0)) "QUESTION_CONTENT_INVALID" else "CONTENT_DIVERSITY_EXHAUSTED"
        logger.info("Level Test generation rejected. outcome={} counters={}", code, totals.counters)
        throw LevelTestException(
            code, 422,
            if (code == "QUESTION_CONTENT_INVALID")
                "Level Test 문항 내용 품질 기준을 만족하는 문제를 생성하지 못했습니다." else "Level Test 중복 방지 기준을 만족하는 문제를 생성하지 못했습니다.",
        )
    }

    private suspend fun response(
        request: JsonObject, candidate: JsonObject, scores: Map<String, Int>, metadata: JsonObject,
        fallback: Boolean, totals: GenerationTotals, provider: String?, model: String?, upload: LevelAudioUpload?,
        deadline: Instant,
    ): LevelQuestionData {
        // 채택이 끝난 문항만 기존 TTS 1회로 합성하여 예약된 LL 저장소에 보관한다.
        val reference = upload?.let { referenceAudio(request, candidate, it, deadline) }
        val result = buildJsonObject {
            listOf("requestId", "sessionId", "questionNumber", "totalQuestions", "domain", "itemType").forEach {
                put(
                    it, request.getValue(it),
                )
            }
            listOf(
                "complexityBand", "instruction", "instructionLanguage", "answerMode", "answerLanguage", "promptText",
                "options",
                "referencePayload", "maxAnswerLength", "maxAudioSeconds",
            ).forEach { put(it, candidate.getValue(it)) }
            put(
                "internalAnswerKey",
                JsonObject(
                    candidate.getValue("internalAnswerKey").jsonObject + mapOf(
                        "selectionPolicy" to (selection(candidate)?.let(::JsonPrimitive) ?: JsonNull),
                        "optionScores" to JsonObject(scores.mapValues { JsonPrimitive(it.value) }),
                    ),
                ),
            )
            put("diversityMetadata", metadata)
            put("generationVersion", "level-test-generation"); put("promptVersion", "level-test-multiskill-prompt")
            put(
                "diversitySummary",
                buildJsonObject {
                    put("policyVersion", "language-learning-diversity"); put(
                    "candidateCount", totals.diversityCount,
                ); put("acceptedCount", 1)
                    put("rejectedExact", totals.reasons["EXACT"] ?: 0); put(
                    "rejectedSimilarity", totals.reasons["SIMILARITY"] ?: 0,
                )
                    put("rejectedStructural", totals.reasons["STRUCTURAL"] ?: 0); put(
                    "rejectedBackgroundKnowledge", totals.reasons["BACKGROUND_KNOWLEDGE"] ?: 0,
                )
                    put("fallbackUsed", fallback)
                },
            )
            put(
                "usage",
                buildJsonObject {
                    put("latencyMs", totals.latency); put("inputTokens", totals.input); put(
                    "outputTokens", totals.output,
                )
                    put("provider", provider?.let(::JsonPrimitive) ?: JsonNull); put(
                    "model", model?.let(::JsonPrimitive) ?: JsonNull,
                )
                    put("promptVersion", "level-test-multiskill-prompt")
                    put("evaluationVersion", JsonNull)
                },
            )
            put("referenceAudio", reference?.let { json.encodeToJsonElement(it) } ?: JsonNull)
        }
        logger.info("Level Test generation accepted. fallback={} counters={}", fallback, totals.counters)
        return json.decodeFromJsonElement<LevelQuestionData>(result)
    }

    private suspend fun referenceAudio(
        request: JsonObject, candidate: JsonObject, upload: LevelAudioUpload, deadline: Instant,
    ): LevelReferenceAudio {
        val type = candidate.getValue("itemType").jsonPrimitive.content
        if (!type.startsWith("LISTENING_") && type != "SPEAKING_REPEAT")
            throw LevelTestException(
                "LEVEL_TEST_REFERENCE_AUDIO_UNEXPECTED", 422, "Reference Audio 업로드 정보가 필요하지 않은 ItemType입니다.",
            )
        val text = candidate.getValue(
            "referencePayload",
        ).jsonObject[if (type == "SPEAKING_REPEAT") "referenceText" else "sourceText"]
            ?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw LevelTestException(
                "LEVEL_TEST_REFERENCE_AUDIO_SOURCE_MISSING", 502, "Level Test Reference Audio 원문이 없습니다.",
            )
        val result = speech.synthesize(
            SpeechSynthesisCommand(
                text, "marin", request.getValue("learningLanguage").jsonPrimitive.content,
                "NORMAL", minOf(deadline, clock.instant().plusSeconds(30)),
                request.getValue("requestId").jsonPrimitive.content,
            ),
        )
        if (result.contentType != upload.contentType)
            throw LevelTestException(
                "LEVEL_TEST_REFERENCE_AUDIO_CONTENT_TYPE", 502,
                "Level Test Reference Audio Content-Type이 업로드 계약과 일치하지 않습니다.",
            )
        publishAudio(upload, result.audioBytes, result.contentType)
        return LevelReferenceAudio(
            upload.objectKey, result.contentType, result.durationSeconds?.let { (it * 1000 + .5).toInt() },
            LevelTestRules.sha256(result.audioBytes),
        )
    }

    private fun selection(candidate: JsonObject) = LevelGenerationVerificationPolicy.selection(
        LevelTestItemType.valueOf(candidate.getValue("itemType").jsonPrimitive.content),
        candidate.getValue("complexityBand").jsonPrimitive.int,
    )

    private fun elapsed(started: Long) = (clock.millis() - started).coerceAtLeast(0)

    private class GenerationTotals {
        var input = 0;
        var output = 0;
        var latency = 0L;
        var diversityCount = 0
        val counters = linkedMapOf<String, Int>()
        val reasons = linkedMapOf<String, Int>()
        fun increment(key: String, amount: Int = 1) {
            counters[key] = (counters[key] ?: 0) + amount
        }

        fun add(input: Int, output: Int, latency: Long) {
            this.input += input; this.output += output; this.latency += latency
        }

        fun record(decision: LevelDiversityDecision) {
            diversityCount++
            decision.reason?.let { reasons[it] = (reasons[it] ?: 0) + 1 }
        }
    }
}
