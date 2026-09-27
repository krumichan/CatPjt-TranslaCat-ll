package jp.co.translacat.languagelearning.features.practice.application

import jp.co.translacat.languagelearning.features.practice.domain.*
import jp.co.translacat.languagelearning.shared.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant

internal fun interface ReadingGenerationPort {
    suspend fun generate(claim: PracticeGenerationClaim): ReadingGeneratedBundle
}

/** 기존 Reading 예산을 stage별로 유지하며, 검증을 끝낸 지문 묶음만 저장 계층에 반환한다. */
internal class ReadingGenerationExecution(
    private val model: ModelExecutionPort,
    private val generationTier: ModelTier = ModelTier.SOL,
    private val clock: Clock = Clock.systemUTC(),
) : ReadingGenerationPort {
    init {
        require(generationTier in setOf(ModelTier.LUNA, ModelTier.SOL))
    }

    override suspend fun generate(claim: PracticeGenerationClaim): ReadingGeneratedBundle {
        val request = Json.decodeFromString<ReadingRequest>(claim.set.requestJson)
        PracticePolicy.requireNewLearning(claim.set.domain, request.mode, request.complexityBand)
        val run = Run(request, claim, clock.instant().plusSeconds(1800))

        // 지문·문항·semantic 검증·모국어 해설은 같은 실행 deadline을 공유한다.
        val passage = run.passage()
        val accepted = run.questions(passage)
        val explained = run.explanations(accepted)
        return ReadingGeneratedBundle(
            "reading-vocabulary-generation", explained.map { it.copy(order = it.order + claim.firstOrder - 1) },
        )
    }

    private inner class Run(val request: ReadingRequest, val claim: PracticeGenerationClaim, val deadline: Instant) {
        private var calls = 0
        private var lastFailure: RuntimeException? = null
        private val slots = ReadingPrompts.slots(request, claim.firstOrder)

        suspend fun passage(): ReadingPassage {
            repeat(3) {
                val schema = when {
                    request.mode == "COMPREHENSION" && request.complexityBand == 1 -> "passage-b1"
                    request.mode == "CONTEXT_INFERENCE" -> "passage-context"
                    else -> "passage-default"
                }
                attempt {
                    val raw = call(
                        "passage", schema, ReadingPrompts.passage(request, claim.firstOrder, claim.previous),
                        generationTier,
                    )
                    ReadingContentPolicy.passage(request, claim.firstOrder, raw)
                }?.let { return it }
            }
            exhausted("READING_PASSAGE_EXHAUSTED")
        }

        suspend fun questions(passage: ReadingPassage): List<PracticeQuestionContent> {
            val accepted = sortedMapOf<Int, PracticeQuestionContent>()
            val verified = mutableSetOf<Int>()
            val generationAttempts = slots.associate { it.globalOrder to 0 }.toMutableMap()
            val semanticAttempts = slots.associate { it.globalOrder to 0 }.toMutableMap()
            val repairAttempts = mutableSetOf<Int>()
            val feedback = mutableMapOf<Int, String>()

            while (verified.size < slots.size) {
                // 성공한 문항을 보존하고 아직 필요한 slot만 기존 2개 batch와 8회 생성 상한으로 요청한다.
                val pending = slots.filter {
                    it.globalOrder !in accepted && it.globalOrder !in verified &&
                        generationAttempts.getValue(it.globalOrder) < 8 && semanticAttempts.getValue(it.globalOrder) < 3
                }
                pending.chunked(2).forEach { batch ->
                    batch.forEach {
                        generationAttempts[it.globalOrder] = generationAttempts.getValue(it.globalOrder) + 1
                    }
                    val response = attempt {
                        val schema =
                            if (request.mode == "COMPREHENSION" && request.complexityBand == 1) "candidate-b1" else "candidate"
                        call(
                            "generation", schema,
                            ReadingPrompts.candidates(
                                request, claim.firstOrder, passage, batch,
                                claim.previous + accepted.values, feedback,
                            ),
                            generationTier,
                        ).jsonObject.getValue("questions").jsonArray
                    }
                    batch.forEach { slot ->
                        val local = slot.globalOrder - claim.firstOrder + 1
                        val matches = response.orEmpty()
                            .filter { (it as? JsonObject)?.get("order")?.jsonPrimitive?.intOrNull == local }
                        val question = if (matches.size == 1) attempt {
                            ReadingContentPolicy.candidate(
                                request, claim.firstOrder, slot, passage, matches.single().jsonObject,
                                claim.previous + accepted.values,
                            )
                        } else null
                        if (question != null) {
                            accepted[slot.globalOrder] = question
                            feedback.remove(slot.globalOrder)
                        } else {
                            val reason =
                                if (matches.size == 1) (lastFailure as? PracticeFailure)?.policyReason else null
                            feedback[slot.globalOrder] = ReadingAssets.retryFeedback(
                                reason ?: "candidate missing or duplicate in provider response",
                            )
                        }
                    }
                }
                if (slots.any { it.globalOrder !in accepted && generationAttempts.getValue(it.globalOrder) >= 8 }) {
                    exhausted("READING_CANDIDATE_EXHAUSTED")
                }

                // 품질 거부 횟수와 검증 프로토콜 재시도는 서로 다른 기존 상한을 유지한다.
                val unverified = accepted.filterKeys { it !in verified }.values.toList()
                if (unverified.isEmpty()) continue
                unverified.forEach { question ->
                    val global = question.order + claim.firstOrder - 1
                    semanticAttempts[global] = semanticAttempts.getValue(global) + 1
                }
                val verdicts = verify(unverified, claim.previous + accepted.filterKeys { it in verified }.values)
                unverified.forEach { question ->
                    val global = question.order + claim.firstOrder - 1
                    val verdict = verdicts.single { it.order == question.order }
                    val position = if (global <= 3) global else global - 3
                    var reason = ReadingSemantics.rejection(
                        verdict, question.correctAnswer.single(), request.mode, question.skillTag, position,
                    )
                    if (reason == null && request.mode == "STRUCTURE" && global !in setOf(
                            3, 5,
                        ) && verdict.boundedStructureScope == false
                    ) {
                        reason = "structure question consumes future passage tasks"
                    }
                    if (reason == null) {
                        verified.add(global)
                        feedback.remove(global)
                        return@forEach
                    }

                    // 기존 distractor-only 보정은 한 번만 허용하고 다시 semantic 판정을 통과해야 채택한다.
                    if (global !in repairAttempts && semanticAttempts.getValue(global) < 3 &&
                        reason == "distractors are too weak or unrelated" && ReadingSemantics.distractorOnly(
                            verdict, question.correctAnswer.single(),
                        )
                    ) {
                        repairAttempts.add(global)
                        val repaired = attempt {
                            val result = ReadingContentPolicy.repaired(
                                question,
                                call("repair", "repair", ReadingPrompts.repair(request, question), generationTier),
                            )
                            ReadingContentPolicy.validateQuestion(
                                request, result, passage,
                                claim.previous + accepted.filterKeys { it != global }.values,
                            )
                            result
                        }
                        if (repaired != null) {
                            accepted[global] = repaired
                            feedback.remove(global)
                            return@forEach
                        }
                    }
                    accepted.remove(global)
                    feedback[global] = ReadingAssets.retryFeedback(reason)
                    if (semanticAttempts.getValue(global) >= 3) throw PracticeFailure(
                        "AI_CONTENT_QUALITY_REJECTED", 422,
                    )
                }
            }
            return accepted.values.toList()
        }

        private suspend fun verify(
            questions: List<PracticeQuestionContent>, previous: List<PracticeQuestionContent>,
        ): List<ReadingVerdict> {
            val original =
                ReadingAssets.schema(if (request.mode == "STRUCTURE") "verification-structure" else "verification")
            val schema = evidenceSchema(original, ReadingSemantics.evidence(questions).map { it.id })
            repeat(3) {
                attempt {
                    val result = call(
                        "verification", "verification",
                        ReadingPrompts.verification(
                            request,
                            claim.firstOrder, questions, previous,
                        ),
                        ModelTier.MINI, schema,
                    )
                    ReadingSemantics.parse(result, questions, request.mode)
                }?.let { return it }
            }
            exhausted("VERIFIER_SCHEMA_INVALID")
        }

        suspend fun explanations(questions: List<PracticeQuestionContent>): List<PracticeQuestionContent> {
            val accepted = mutableMapOf<Int, String>()
            // Nano 두 번 뒤 남은 항목만 Mini 한 번으로 국문 해설을 생성한다.
            listOf(ModelTier.NANO, ModelTier.NANO, ModelTier.MINI).forEach { tier ->
                questions.filter { it.order !in accepted }.chunked(4).forEach { batch ->
                    val result = attempt {
                        call("explanation", "explanation", ReadingPrompts.explanation(request, batch), tier)
                            .jsonObject.getValue("explanations").jsonArray
                    }
                    val seen = mutableSetOf<Int>()
                    result.orEmpty().forEach { value ->
                        val item = value as? JsonObject ?: return@forEach
                        val order = item["order"]?.jsonPrimitive?.intOrNull ?: return@forEach
                        if (batch.none { it.order == order } || !seen.add(order)) return@forEach
                        val text = item["text"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                        attempt {
                            ReadingContentPolicy.explanation(
                                request.originLanguage, text,
                            )
                        }?.let { accepted[order] = it }
                    }
                }
            }
            if (questions.any { it.order !in accepted }) exhausted("READING_EXPLANATION_EXHAUSTED")
            return questions.map { it.copy(explanationOrigin = accepted.getValue(it.order)) }
        }

        private suspend fun call(
            stage: String, schemaName: String, prompt: String, tier: ModelTier,
            schema: JsonObject = ReadingAssets.schema(schemaName),
        ): JsonElement {
            // SDK 재시도는 공통 실행 경계에서 0회이며 각 호출은 상위 deadline과 기존 stage timeout 중 짧은 쪽을 쓴다.
            val generation = stage in setOf("passage", "generation", "repair")
            val seconds = if (generation && generationTier == ModelTier.SOL) 80L else 45L
            val callDeadline = minOf(deadline, clock.instant().plusSeconds(seconds))
            val maxTokens = if (generation && tier == ModelTier.SOL) 8192 else if (stage in setOf(
                    "repair", "explanation",
                )
            ) 2048 else 4096
            calls++
            return model.execute(
                ModelExecutionCommand(
                    traceId = "practice-${claim.set.id}-${claim.firstOrder}-$calls",
                    instructions = ReadingAssets.system(stage),
                    messages = listOf(ModelMessage("user", prompt)), tier = tier, maxOutputTokens = maxTokens,
                    deadlineUtc = callDeadline, responseSchema = schema,
                    schemaName = "practice_$schemaName".replace('-', '_'),
                    strict = false, taskName = "ll.reading.$stage",
                ),
            ).output
        }

        private suspend fun <T> attempt(action: suspend () -> T): T? = try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ModelExecutionFailure) {
            // 거부와 잘못된 실행 계약은 품질 재생성으로 숨기지 않는다.
            if (failure.code.contains("REFUS") || failure.code == "MODEL_EXECUTION_REQUEST_INVALID") throw failure
            lastFailure = failure
            null
        } catch (failure: PracticeFailure) {
            lastFailure = failure
            null
        } catch (_: IllegalArgumentException) {
            lastFailure = PracticeFailure("AI_SCHEMA_INVALID", 422)
            null
        } catch (_: NoSuchElementException) {
            lastFailure = PracticeFailure("AI_SCHEMA_INVALID", 422)
            null
        }

        private fun exhausted(code: String): Nothing {
            val failure = lastFailure
            if (failure is ModelExecutionFailure) throw failure
            throw PracticeFailure(code, 422)
        }
    }

    private fun evidenceSchema(schema: JsonObject, ids: List<String>): JsonObject {
        val root = schema.toMutableMap()
        val properties = root.getValue("properties").jsonObject.toMutableMap()
        val verdicts = properties.getValue("verdicts").jsonObject.toMutableMap()
        val item = verdicts.getValue("items").jsonObject.toMutableMap()
        val fields = item.getValue("properties").jsonObject.toMutableMap()
        val spans = fields.getValue("stemEvidenceSpanIds").jsonObject.toMutableMap()
        val spanItem = spans.getValue("items").jsonObject.toMutableMap()
        spanItem["enum"] = JsonArray(ids.map(::JsonPrimitive))
        spans["items"] = JsonObject(spanItem)
        fields["stemEvidenceSpanIds"] = JsonObject(spans)
        item["properties"] = JsonObject(fields)
        verdicts["items"] = JsonObject(item)
        properties["verdicts"] = JsonObject(verdicts)
        root["properties"] = JsonObject(properties)
        return JsonObject(root)
    }
}
