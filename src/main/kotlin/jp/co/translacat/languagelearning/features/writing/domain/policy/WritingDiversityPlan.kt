package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*
import java.security.MessageDigest

internal data class WritingDiversityPlan(
    val mode: String,
    val allowedScenarios: List<String>,
    val preferredScenarios: List<String>,
    val allowedIntents: List<String>,
    val preferredIntents: List<String>,
    val eligibleKeywordKeys: List<String>,
) {
    fun payload(): JsonObject = buildJsonObject {
        put("policyVersion", "writing-set-diversity-v1")
        put("mode", mode)
        put("windowSize", 5)
        put("maxSameScenario", if (mode == "UNCONSTRAINED") JsonPrimitive(2) else JsonNull)
        put("maxSameIntent", 2)
        put("allowedScenarioCategories", strings(allowedScenarios))
        put("preferredScenarioCategories", strings(preferredScenarios))
        put("allowedCommunicativeIntents", strings(allowedIntents))
        put("preferredCommunicativeIntents", strings(preferredIntents))
        put("eligibleKeywordKeys", strings(eligibleKeywordKeys))
        put("minimumRelevantKeywords", if (eligibleKeywordKeys.isEmpty()) 0 else 1)
    }

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
}

/** Python diversity_policy.py의 현재 세트 window/키워드 범위와 결정적 선호 순서. */
internal object WritingDiversityPolicy {
    private val scenarios = listOf(
        "DAILY_LIFE", "WORK", "TRAVEL", "SHOPPING", "FOOD", "SERVICE", "LEARNING",
        "HOBBY", "DIGITAL_LIFE", "SOCIAL", "SCHEDULE", "HEALTH_GENERAL",
    )
    private val intents = listOf(
        "DESCRIBE", "REQUEST", "CONFIRM", "REPORT", "SUGGEST", "DECLINE", "APOLOGIZE",
        "COMPARE", "EXPLAIN_REASON", "ASK_INFORMATION", "GIVE_INSTRUCTION", "EXPRESS_PREFERENCE", "SUMMARIZE",
    )

    fun plan(request: JsonObject): WritingDiversityPlan {
        val context = request["diversityContext"] as? JsonObject ?: JsonObject(emptyMap())
        fun entries(name: String) = (context[name] as? JsonArray ?: JsonArray(emptyList()))
            .map { it.jsonObject }.filter { it["sourceType"]?.jsonPrimitive?.content == "WRITING" }

        val window = entries("currentSession").takeLast(4)
        fun counts(values: List<JsonObject>, key: String) = values.mapNotNull {
            (it[key] as? JsonPrimitive)?.contentOrNull
        }.groupingBy { it }.eachCount()

        val scenarioCounts = counts(window, "scenarioCategory")
        val intentCounts = counts(window, "communicativeIntent")
        val recentCounts = counts(entries("sameFeatureRecent"), "scenarioCategory")
        val keys = (request["selectedKeywords"] as? JsonArray ?: JsonArray(emptyList()))
            .map { it.jsonObject.getValue("key").jsonPrimitive.content }.distinct()
        val allowedScenarios = scenarios.filter { keys.isNotEmpty() || (scenarioCounts[it] ?: 0) < 2 }
        val allowedIntents = intents.filter { (intentCounts[it] ?: 0) < 2 }
        val seed = "${
            (request["snapshotId"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: request.getValue("requestId").jsonPrimitive.content
        }|${(request["generationDate"] as? JsonPrimitive)?.contentOrNull ?: "None"}|${
            request.getValue(
                "writingType",
            ).jsonPrimitive.content
        }"

        fun tie(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest("$seed|$value".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val preferredScenarios = allowedScenarios.sortedWith(
            compareBy<String> { scenarioCounts[it] ?: 0 }
                .thenBy { recentCounts[it] ?: 0 }.thenBy(::tie),
        ).take(4)
        val preferredIntents = allowedIntents.sortedWith(
            compareBy<String> { intentCounts[it] ?: 0 }
                .thenBy(::tie),
        ).take(4)
        return WritingDiversityPlan(
            if (keys.isEmpty()) "UNCONSTRAINED" else "KEYWORD_FOCUSED",
            allowedScenarios, preferredScenarios, allowedIntents, preferredIntents, keys,
        )
    }
}
