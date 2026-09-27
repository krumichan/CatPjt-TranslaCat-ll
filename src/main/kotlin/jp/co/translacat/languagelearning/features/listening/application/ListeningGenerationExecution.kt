package jp.co.translacat.languagelearning.features.listening.application

import jp.co.translacat.languagelearning.features.listening.domain.policy.*
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingDiversityValidator
import jp.co.translacat.languagelearning.shared.ai.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.Instant

/** Python의 기존 후보 3회·history 완화 1회 규칙을 그대로 LL에서 실행한다. */
internal class ListeningGenerationExecution(
    private val model: ModelExecutionPort,
    private val timeoutSeconds: Long = 30,
    private val budget: ListeningExecutionBudget = ListeningExecutionBudget(),
) {
    suspend fun generate(request: JsonObject, deadline: Instant, automaticRetryLimit: Int = 2): List<JsonObject> {
        require(automaticRetryLimit in 0..2)
        val set = request.getValue("setContext").jsonObject
        val count = set.getValue("itemCount").jsonPrimitive.int.also { require(it in 1..30) }
        require((request["manualRetryAttempt"] as? JsonPrimitive)?.intOrNull?.let { it in 0..1 } != false)
        if (request["durationCorrection"] is JsonObject) require(count == 1)
        ListeningDuration.demand(request)
        val context = effectiveContext(request)
        val accepted = mutableListOf<JsonObject>()
        val diversity = mutableListOf<Pair<String, JsonObject>>()
        val candidates = mutableListOf<JsonObject>()
        var retries = 0

        // 후보 채택과 기술 오류는 분리하며 모든 호출에 같은 deadline을 사용한다.
        for (attempt in 0..2) {
            val missing = count - accepted.size
            if (missing <= 0) break
            val pool = maxOf(missing, minOf(missing * 2, 40))
            val current = (context["currentSession"] as? JsonArray).orEmpty() + accepted.map { item ->
                val metadata = item.getValue("diversityMetadata").jsonObject
                JsonObject(
                    metadata + mapOf(
                        "sourceType" to JsonPrimitive("LISTENING"), "content" to item.getValue("sourceText"),
                        "ageDays" to JsonPrimitive(0),
                    ),
                )
            }
            val candidateRequest = JsonObject(
                request + mapOf(
                    "setContext" to JsonObject(set + ("itemCount" to JsonPrimitive(pool))),
                    "diversityContext" to JsonObject(context + ("currentSession" to JsonArray(current))),
                ),
            )
            val result = try {
                budget.call(deadline, timeoutSeconds) { callDeadline ->
                    model.execute(
                        ModelExecutionCommand(
                            request.getValue("requestId").jsonPrimitive.content,
                            ListeningAssets.instructions("generation"),
                            listOf(ModelMessage("user", prompt(candidateRequest))),
                            ModelTier.LUNA, 8192, callDeadline, ListeningAssets.schema("generation"),
                            "ListeningGenerationPayload",
                            taskName = "LANGUAGE_LEARNING_LISTENING_GENERATION",
                        ),
                    )
                }
            } catch (failure: ModelExecutionFailure) {
                if (failure.retryable && attempt < 2 && retries < automaticRetryLimit) {
                    retries++
                    continue
                }
                throw failure
            }
            val response = result.output as? JsonObject ?: throw ListeningProtocolFailure()
            val raw = response["items"] as? JsonArray ?: JsonArray(emptyList())
            val salvaged = raw.mapNotNull { item -> salvage(item) }
            if (salvaged.isEmpty() && attempt == 2) throw ListeningProtocolFailure()
            val validator = WritingDiversityValidator(context)
            for (candidate in salvaged) {
                candidates += candidate
                val finalized = finalize(request, candidate) ?: continue
                val content = finalized.getValue("sourceText").jsonPrimitive.content
                val decision =
                    validator.validate(content, finalized.getValue("diversityMetadata").jsonObject, diversity)
                if (!decision.accepted) continue
                val metadata = JsonObject(
                    decision.metadata + mapOf(
                        "contentHash" to finalized.getValue("contentHash"),
                        "similarityKey" to finalized.getValue("similarityKey"),
                    ),
                )
                accepted += JsonObject(finalized + ("diversityMetadata" to metadata))
                diversity += content to metadata
                if (accepted.size >= count) break
            }
        }

        // 이전 이력의 유사도 기준만 기존 범위에서 완화하며 같은 batch의 중복은 유지한다.
        if (accepted.size < count) {
            val validator = WritingDiversityValidator(context, relaxedHistory = true)
            for (candidate in candidates) {
                val finalized = finalize(request, candidate) ?: continue
                if (accepted.any { it["contentHash"] == finalized["contentHash"] }) continue
                val content = finalized.getValue("sourceText").jsonPrimitive.content
                val decision =
                    validator.validate(content, finalized.getValue("diversityMetadata").jsonObject, diversity)
                if (!decision.accepted) continue
                val metadata = JsonObject(
                    decision.metadata + mapOf(
                        "contentHash" to finalized.getValue("contentHash"),
                        "similarityKey" to finalized.getValue("similarityKey"),
                    ),
                )
                accepted += JsonObject(finalized + ("diversityMetadata" to metadata))
                diversity += content to metadata
                if (accepted.size >= count) break
            }
        }
        if (accepted.size < count) throw ListeningProtocolFailure("CONTENT_DIVERSITY_EXHAUSTED")
        return accepted.take(count)
            .mapIndexed { index, item -> JsonObject(item + ("itemIndex" to JsonPrimitive(index + 1))) }
    }

    private fun salvage(raw: JsonElement): JsonObject? {
        val candidate = raw as? JsonObject ?: return null
        val normalized = candidate.toMutableMap()
        (candidate["comprehensionFocus"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { focus ->
            val value = focus.trim().uppercase().replace('-', '_').replace(' ', '_')
            if (value in setOf(
                    "GIST", "DETAIL", "INTENT", "INFERENCE", "NEXT_ACTION",
                )
            ) normalized["comprehensionFocus"] = JsonPrimitive(value)
        }
        return try {
            val root = ListeningAssets.schema("generation")
            ListeningSchema.decode(
                JsonObject(normalized),
                root.getValue("\$defs").jsonObject.getValue("GeneratedListeningItemPayload").jsonObject, root,
            ).jsonObject
        } catch (_: ListeningProtocolFailure) {
            null
        }
    }

    internal fun finalize(request: JsonObject, item: JsonObject): JsonObject? {
        val metadata = item["diversityMetadata"] as? JsonObject ?: return null
        val set = request.getValue("setContext").jsonObject
        val complexity = request["languageComplexity"] as? JsonObject
        val base = (complexity?.get("baseComplexityBand") as? JsonPrimitive)?.intOrNull ?: 3
        val band = (complexity?.get("targetComplexityBand") as? JsonPrimitive)?.intOrNull ?: when (set.getValue(
            "difficulty",
        ).jsonPrimitive.content) {
            "EASY" -> maxOf(1, base - 1)
            "CHALLENGE" -> minOf(5, base + 1)
            else -> base
        }
        if ((item["languageComplexityBand"] as? JsonPrimitive)?.intOrNull != band || item.getValue(
                "safety",
            ).jsonObject.getValue("passed").jsonPrimitive.boolean != true
        ) return null
        val mode = set.getValue("learningMode").jsonPrimitive.content
        fun none(key: String) = item[key] == null || item[key] == JsonNull
        fun array(key: String) = (item[key] as? JsonArray).orEmpty()
        val modeValid = when (mode) {
            "DICTATION" -> none("question") && array("options").isEmpty() && none("correctOptionKey") && none(
                "comprehensionFocus",
            ) && array("summaryKeyPoints").isEmpty()

            "COMPREHENSION" -> {
                val keys = array("options").map { it.jsonObject.getValue("key").jsonPrimitive.content }
                !((item["question"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) && keys.size == 4 && keys.toSet() == setOf(
                    "A", "B", "C", "D",
                ) &&
                    item["correctOptionKey"]?.jsonPrimitive?.content in keys && item["comprehensionFocus"]?.jsonPrimitive?.content in setOf(
                    "GIST", "DETAIL", "INTENT", "INFERENCE", "NEXT_ACTION",
                ) && array("summaryKeyPoints").isEmpty()
            }

            "SUMMARY" -> array("summaryKeyPoints").count { it.jsonPrimitive.content.isNotBlank() } in 2..6 && none(
                "question",
            ) && array("options").isEmpty() && none("correctOptionKey") && none("comprehensionFocus")

            else -> false
        }
        if (!modeValid) return null
        val language = request.getValue("userContext").jsonObject.getValue("learningLanguage").jsonPrimitive.content
        val source = item.getValue("sourceText").jsonPrimitive.content
        val normalized = ListeningText.normalize(source, language)
        val correction = request["durationCorrection"] as? JsonObject
        if (correction != null && normalized.text == ListeningText.normalize(
                correction.getValue("previousSourceText").jsonPrimitive.content, language,
            ).text
        ) return null
        val trimmed = source.trim()
        if (ListeningDuration.correctionFloor(request)
                ?.let { trimmed.codePointCount(0, trimmed.length) < it } == true
        ) return null
        return JsonObject(
            item + mapOf(
                "normalizedSourceText" to JsonPrimitive(normalized.text),
                "contentHash" to JsonPrimitive(hash(normalized.text)),
                "similarityKey" to JsonPrimitive(hash(normalized.tokens.joinToString(""))),
                "diversityMetadata" to metadata,
                "durationDemand" to ListeningDuration.demand(request).payload(),
                "qualityCorrectionCount" to JsonPrimitive(if (correction == null) 0 else 1),
            ),
        )
    }

    private fun effectiveContext(request: JsonObject): JsonObject {
        val original = request["diversityContext"] as? JsonObject ?: JsonObject(emptyMap())
        val constraints = request["constraints"] as? JsonObject ?: JsonObject(emptyMap())
        val exact =
            ((original["exactContentHashes90d"] as? JsonArray).orEmpty() + (constraints["recentContentHashes"] as? JsonArray).orEmpty()).distinct()
                .take(200)
        val history =
            (original["sameFeatureRecent"] as? JsonArray).orEmpty() + (constraints["recentSimilaritySummaries"] as? JsonArray).orEmpty()
                .filter { it.jsonPrimitive.content.isNotBlank() }
                .map { buildJsonObject { put("sourceType", "LISTENING"); put("content", it) } }
        return JsonObject(
            original + mapOf(
                "exactContentHashes90d" to JsonArray(exact), "sameFeatureRecent" to JsonArray(history.take(80)),
            ),
        )
    }

    internal fun prompt(request: JsonObject): String {
        val set = request.getValue("setContext").jsonObject
        val user = request.getValue("userContext").jsonObject
        val count = set.getValue("itemCount").jsonPrimitive.int
        val learning = user.getValue("learningLanguage").jsonPrimitive.content
        val origin = user.getValue("originLanguage").jsonPrimitive.content
        val mode = set.getValue("learningMode").jsonPrimitive.content
        return "Generate exactly $count listening items. itemIndex MUST be 1-based and contain each integer from 1 through $count " +
            "exactly once; never return itemIndex=0. sourceText MUST be written only in learningLanguage ($learning). " +
            "referenceMeanings and keyMeaningUnits MUST be written only in originLanguage ($origin); never default them to English unless originLanguage is English. " +
            "The requested learningMode is $mode; apply that mode contract exactly. Plan for the interior duration target; the final waveform, not your estimate, " +
            "must satisfy the effective duration range.\n\n" + JsonObject(
            request + ("generationDurationGuidance" to ListeningDuration.guidance(request)),
        )
    }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
