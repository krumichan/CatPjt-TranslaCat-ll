package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelTestItemType
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelGenerationVerificationPolicy
import jp.co.translacat.languagelearning.shared.ai.*
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant

internal data class LevelGenerationVerification(
    val reason: String?, val verdict: JsonObject?,
    val calls: Int, val inputTokens: Int, val outputTokens: Int, val optionScores: Map<String, Int> = emptyMap(),
)

/** 기존 생성 파이프라인의 독립 검증 호출을 이전한다. 생성 루프는 별도 연결 검증 전까지 legacy를 유지한다. */
internal class LevelGenerationVerificationExecution(
    private val model: ModelExecutionPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun choice(
        key: String, origin: String, learning: String, candidate: JsonObject,
        deadline: Instant,
    ): LevelGenerationVerification {
        val selection = LevelGenerationVerificationPolicy.selection(
            LevelTestItemType.valueOf(candidate.getValue("itemType").jsonPrimitive.content),
            candidate.getValue("complexityBand").jsonPrimitive.int,
        ) ?: return LevelGenerationVerification(null, null, 0, 0, 0)
        val verdicts = mutableListOf<JsonObject>()
        var input = 0
        var output = 0

        // 답 키를 숨긴 독립 검증을 기존 정책의 1회 또는 2회만 수행한다.
        for (pass in 1..LevelGenerationVerificationPolicy.passes(selection)) {
            val (verdict, result) = call(
                key, "choice-verification", "LevelTestChoiceSemanticVerificationPayload",
                "LANGUAGE_LEARNING_LEVEL_TEST_CHOICE_VERIFICATION",
                choicePrompt(candidate, origin, learning, selection, pass), deadline,
            )
            verdicts += verdict
            input += result.inputTokens
            output += result.outputTokens
            val reason = LevelGenerationVerificationPolicy.rejection(candidate, verdict, selection)
            if (reason != null) return LevelGenerationVerification(reason, verdict, verdicts.size, input, output)
        }
        return LevelGenerationVerification(
            null, verdicts.last(), verdicts.size, input, output,
            LevelGenerationVerificationPolicy.optionScores(candidate, selection, verdicts),
        )
    }

    suspend fun task(
        key: String, origin: String, learning: String, candidate: JsonObject,
        deadline: Instant,
    ): LevelGenerationVerification {
        val (verdict, result) = call(
            key, "task-verification", "LevelTestTaskSufficiencyVerificationPayload",
            "LANGUAGE_LEARNING_LEVEL_TEST_TASK_SUFFICIENCY_VERIFICATION", taskPrompt(candidate, origin, learning),
            deadline,
        )
        return LevelGenerationVerification(
            LevelGenerationVerificationPolicy.taskRejection(candidate, verdict), verdict,
            1, result.inputTokens, result.outputTokens,
        )
    }

    private suspend fun call(
        key: String, asset: String, schemaName: String, task: String, prompt: String,
        deadline: Instant,
    ): Pair<JsonObject, ModelExecutionResult> {
        val schema = Json.parseToJsonElement(resource("$asset-schema.json")).jsonObject
        val result = try {
            model.execute(
                ModelExecutionCommand(
                    key, resource("$asset-system-prompt.txt"),
                    listOf(ModelMessage("user", prompt)), ModelTier.MINI, 2048,
                    minOf(deadline, clock.instant().plusSeconds(30)), schema, schemaName, taskName = task,
                ),
            )
        } catch (failure: ModelExecutionFailure) {
            // 원본 독립 검증은 후보 생성 재시도를 유발하지 않으며 기술 오류를 즉시 전달한다.
            throw LevelTestException(
                failure.code, LevelModelFailurePolicy.status(failure), "Level Test 검증 Provider 호출에 실패했습니다.",
            )
        }

        // 합법적인 품질 거절은 verdict로 반환하지만 프로토콜 오류는 후보 거절로 삼키지 않는다.
        val verdict = try {
            LevelPydanticSchema.decode(result.output, schema)
        } catch (_: LevelSchemaFailure) {
            throw LevelTestException("LEVEL_TEST_VERIFIER_SCHEMA_INVALID", 502, "Level Test 검증 응답 Schema가 유효하지 않습니다.")
        }
        return verdict to result
    }

    internal fun choicePrompt(
        candidate: JsonObject, origin: String, learning: String, selection: String, pass: Int,
    ): String {
        val prompt = candidate.getValue("promptText").jsonPrimitive.content
        val blank = Regex("(?:_{2,}|＿{2,})")
        val options = candidate.getValue("options").jsonArray.map { element ->
            val option = element.jsonObject
            val text = option.getValue("text").jsonPrimitive.content
            val match = blank.find(prompt)
            buildJsonObject {
                put("key", option.getValue("key")); put("optionText", text)
                put("completedText", if (match == null) prompt else prompt.replaceRange(match.range, text))
            }
        }
        val evidence = buildJsonObject {
            if (candidate.getValue("itemType").jsonPrimitive.content.startsWith("LISTENING_")) {
                val source =
                    candidate.getValue("referencePayload").jsonObject["sourceText"]?.jsonPrimitive?.contentOrNull
                if (!source.isNullOrBlank()) put("sourceText", source.trim())
            }
        }
        val payload = buildJsonObject {
            put("originLanguage", origin); put("learningLanguage", learning)
            listOf("domain", "itemType", "complexityBand").forEach { put(it, candidate.getValue(it)) }
            put("selectionPolicy", selection); put("verificationPass", pass)
            put("instruction", candidate.getValue("instruction")); put("promptText", prompt)
            put("options", JsonArray(options)); put("verifierEvidence", evidence)
        }
        return (if (pass <= 1) "Independently judge plausible options and the single best option. " else
            "Adversarial second pass: actively search for a rival option that could tie or outrank the apparent best answer before returning your verdict. ") +
            "The generator's expected answer key is deliberately omitted.\n\n" + payload
    }

    internal fun taskPrompt(candidate: JsonObject, origin: String, learning: String): String {
        val payload = buildJsonObject {
            put("originLanguage", origin); put("learningLanguage", learning)
            listOf("domain", "itemType", "complexityBand", "instruction", "promptText").forEach {
                put(
                    it, candidate.getValue(it),
                )
            }
            val ref = candidate.getValue("referencePayload").jsonObject
            listOf("providedFacts", "requiredIntents", "responseConstraints").forEach {
                put(
                    it, ref[it] ?: JsonArray(emptyList()),
                )
            }
        }
        return "Verify that this guided task can be answered using language ability rather than inventing substantive content.\n\n" + payload
    }

    private fun resource(name: String) =
        checkNotNull(javaClass.getResource("/leveltest/$name")).readText().replace("\r\n", "\n")
}
