package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelGenerationAssets
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelGenerationCandidatePolicy
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelGenerationNormalizer
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningText
import jp.co.translacat.languagelearning.shared.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant

internal data class LevelGenerationStage<T>(
    val value: T?, val inputTokens: Int = 0, val outputTokens: Int = 0,
    val latencyMs: Long = 0, val failureCode: String? = null,
)

/** 기존 어휘 설계와 한 번의 보정을 실행한다. 설계 실패 시에도 후속 독립 검증은 생략하지 않는다. */
internal class LevelVocabDesignExecution(
    private val model: ModelExecutionPort, private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun plan(
        request: JsonObject, refill: Int, rejected: List<String>, deadline: Instant,
    ): LevelGenerationStage<List<JsonObject>> {
        val started = clock.millis()
        val schema = LevelGenerationAssets.baseSchema("vocab-design").toMutableMap()
        val preferred = request.getValue("preferredScenarioCategories").jsonArray
        if (preferred.isNotEmpty()) {
            val defs = schema.getValue("\$defs").jsonObject.toMutableMap()
            val design = defs.getValue("LevelTestVocabContextDesign").jsonObject.toMutableMap()
            design["properties"] = JsonObject(
                design.getValue("properties").jsonObject + ("scenarioCategory" to buildJsonObject {
                    put("type", "string"); put("enum", preferred)
                }),
            )
            defs["LevelTestVocabContextDesign"] = JsonObject(design)
            schema["\$defs"] = JsonObject(defs)
        }

        // 기존 설계 단계는 최적화이므로 기술·schema 실패를 진단과 함께 direct generation fallback으로 돌린다.
        val result = try {
            model.execute(
                ModelExecutionCommand(
                    request.getValue("requestId").jsonPrimitive.content,
                    LevelGenerationAssets.instructions("vocab-design"),
                    listOf(ModelMessage("user", planPrompt(request, refill, rejected))),
                    ModelTier.LUNA, 4096, minOf(deadline, clock.instant().plusSeconds(30)), JsonObject(schema),
                    "LevelTestVocabContextDesignPayload",
                    taskName = "LANGUAGE_LEARNING_LEVEL_TEST_VOCAB_CONTEXT_DESIGN",
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ModelExecutionFailure) {
            return LevelGenerationStage(null, latencyMs = elapsed(started), failureCode = failure.code)
        }
        val parsed = try {
            val payload = normalizeDesigns(result.output as? JsonObject ?: throw LevelSchemaFailure(emptyList()))
            LevelPydanticSchema.decode(payload, LevelGenerationAssets.baseSchema("vocab-design"))
        } catch (_: LevelSchemaFailure) {
            return LevelGenerationStage(
                null, result.inputTokens, result.outputTokens, elapsed(started), "LEVEL_TEST_DESIGN_SCHEMA_INVALID",
            )
        }
        val designs = parsed.getValue("designs").jsonArray.map { it.jsonObject }
        val targetKeys = designs.map { compactTarget(it.getValue("targetExpression").jsonPrimitive.content) }
        if (designs.map { it.getValue("designId") }.toSet() != setOf(
                JsonPrimitive("A"), JsonPrimitive("B"),
            ) || targetKeys.distinct().size != designs.size
        )
            return LevelGenerationStage(
                null, result.inputTokens, result.outputTokens, elapsed(started), "LEVEL_TEST_DESIGN_SCHEMA_INVALID",
            )
        if (preferred.isNotEmpty() && designs.any { it.getValue("scenarioCategory") !in preferred })
            return LevelGenerationStage(
                null, result.inputTokens, result.outputTokens, elapsed(started), "LEVEL_TEST_DESIGN_SCENARIO_INVALID",
            )
        return LevelGenerationStage(designs, result.inputTokens, result.outputTokens, elapsed(started))
    }

    suspend fun repair(
        request: JsonObject, candidate: JsonObject, design: JsonObject, verdict: JsonObject,
        selection: String, deadline: Instant,
    ): LevelGenerationStage<JsonObject> {
        val started = clock.millis()
        val result = try {
            model.execute(
                ModelExecutionCommand(
                    request.getValue("requestId").jsonPrimitive.content,
                    LevelGenerationAssets.instructions("vocab-repair"),
                    listOf(ModelMessage("user", repairPrompt(candidate, design, verdict, selection))),
                    ModelTier.MINI, 4096, minOf(deadline, clock.instant().plusSeconds(30)),
                    LevelGenerationAssets.baseSchema("vocab-repair"),
                    "LevelTestVocabContextRepairPayload",
                    taskName = "LANGUAGE_LEARNING_LEVEL_TEST_VOCAB_CONTEXT_REPAIR",
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ModelExecutionFailure) {
            return LevelGenerationStage(null, latencyMs = elapsed(started), failureCode = failure.code)
        }

        // 보정은 원본과 동일하게 한 번만 시도하며 결과도 기존 정규화·후보 Schema를 다시 통과해야 한다.
        val raw = (result.output as? JsonObject)?.get("candidate") as? JsonObject
            ?: return LevelGenerationStage(
                null, result.inputTokens, result.outputTokens, elapsed(started), "LEVEL_TEST_REPAIR_SCHEMA_INVALID",
            )
        val normalized = LevelGenerationNormalizer.normalize(
            buildJsonObject { put("candidates", JsonArray(listOf(raw))) },
            request.getValue("originLanguage").jsonPrimitive.content,
            request.getValue("learningLanguage").jsonPrimitive.content,
        )
        val parsed = try {
            LevelGenerationCandidateParser.parse(normalized.output.getValue("candidates").jsonArray.first().jsonObject)
        } catch (_: LevelSchemaFailure) {
            null
        }
        return LevelGenerationStage(
            parsed, result.inputTokens, result.outputTokens, elapsed(started),
            if (parsed == null) "LEVEL_TEST_REPAIR_SCHEMA_INVALID" else null,
        )
    }

    internal fun planPrompt(request: JsonObject, refill: Int, rejected: List<String>): String =
        "Design two compact target-first vocabulary semantic blueprints. Do not generate final wording, distractors, or presentation metadata.\n\n" + buildJsonObject {
            put("originLanguage", request.getValue("originLanguage")); put(
            "learningLanguage", request.getValue("learningLanguage"),
        )
            put("complexityBand", request.getValue("targetComplexityBand")); put(
            "preferredScenarioCategories", request.getValue("preferredScenarioCategories"),
        )
            put("refillAttempt", refill); put("rejectedReasons", JsonArray(rejected.takeLast(8).map(::JsonPrimitive)))
        }

    internal fun repairPrompt(
        candidate: JsonObject, design: JsonObject, verdict: JsonObject, selection: String,
    ): String =
        "Repair this rejected item once. Strengthen the context or replace distractors, but preserve the approved target and design identity.\n\n" + buildJsonObject {
            put("selectionPolicy", selection); put("design", design); put("candidate", candidate); put(
            "verification", verdict,
        )
        }

    internal fun binding(candidate: JsonObject, designs: List<JsonObject>): Pair<JsonObject?, String?> {
        val design = designs.firstOrNull { it["designId"] == candidate["generationPlanId"] }
            ?: return null to "VOCAB_CONTEXT_CHOICE가 서버 승인 generationPlanId를 따르지 않았습니다."
        val correct = candidate.getValue("options").jsonArray.map { it.jsonObject }.firstOrNull {
            it["key"] == candidate.getValue("internalAnswerKey").jsonObject["correctOptionKey"]
        } ?: return null to "VOCAB_CONTEXT_CHOICE 정답 Option을 확인할 수 없습니다."
        if (LevelGenerationCandidatePolicy.choiceText(correct.getValue("text").jsonPrimitive.content) !=
            LevelGenerationCandidatePolicy.choiceText(design.getValue("targetExpression").jsonPrimitive.content)
        )
            return null to "VOCAB_CONTEXT_CHOICE가 서버 승인 targetExpression을 정답으로 사용하지 않았습니다."
        val metadata = candidate.getValue("diversityMetadata").jsonObject
        if (metadata["scenarioCategory"] != design["scenarioCategory"]) return null to "VOCAB_CONTEXT_CHOICE가 서버 승인 scenarioCategory를 변경했습니다."
        if (metadata["communicativeIntent"] != design["communicativeIntent"]) return null to "VOCAB_CONTEXT_CHOICE가 서버 승인 communicativeIntent를 변경했습니다."
        return design to null
    }

    private fun normalizeDesigns(value: JsonObject): JsonObject {
        val designs = value["designs"] as? JsonArray ?: return value
        val aliases = linkedMapOf(
            "targetExpression" to "target_expression", "targetMeaning" to "target_meaning",
            "semanticConstraint" to "semantic_constraint", "scenarioCategory" to "scenario_category",
            "communicativeIntent" to "communicative_intent",
        )
        return buildJsonObject {
            put(
                "designs",
                JsonArray(
                    designs.take(2).mapIndexedNotNull { index, raw ->
                        val source = raw as? JsonObject ?: return@mapIndexedNotNull null
                        buildJsonObject {
                            put("designId", "AB"[index].toString())
                            aliases.forEach { (camel, snake) ->
                                (source[camel] ?: source[snake])?.let {
                                    put(
                                        camel, it,
                                    )
                                }
                            }
                        }
                    },
                ),
            )
        }
    }

    private fun elapsed(started: Long) = (clock.millis() - started).coerceAtLeast(0)
    private fun compactTarget(value: String) = Regex("(?U)[\\s\\x1c-\\x1f]+").replace(ListeningText.casefold(value), "")
}
