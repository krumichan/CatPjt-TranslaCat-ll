package jp.co.translacat.languagelearning.features.overview.application

import kotlinx.serialization.json.*
import java.time.LocalDateTime

/** 최근 30개 활동 선별과 출처별 근거 문턱은 기존 통합 프로필 정책 그대로다. */
internal object RecentInsightProjection {
    private data class Signal(
        val pattern: String, val direction: String, val focus: String?, val confidence: Double, val metric: String?,
    )

    private data class Activity(
        val source: String, val time: LocalDateTime, val confidence: Double, val validity: Double,
        val assistance: List<String>, val signals: List<Signal>,
    )

    private data class Aggregate(
        val pattern: String, val direction: String, var evidence: Int = 0, var weight: Double = 0.0,
        var focus: String? = null, val sources: MutableMap<String, Int> = linkedMapOf(),
    )

    fun project(
        writing: JsonObject, speaking: JsonObject, listeningMetrics: JsonArray, source: String?, limit: Int,
    ): JsonArray {
        // 출처 필터보다 먼저 합쳐진 활동의 최신 30개를 고른다. 동시각은 Writing을 우선한다.
        val activities = (writing.rows("evaluations").map(::writingActivity) +
            speaking.rows("evaluations")
                .filter { it.text("status").equals("EVALUATED", true) && it.number("overallScore") != null }
                .map(::speakingActivity)).sortedByDescending { it.time }.take(30)
        val aggregates = linkedMapOf<String, Aggregate>()
        fun add(sourceName: String, signal: Signal, weight: Double) {
            val value = aggregates.getOrPut("${signal.direction}:${signal.pattern.lowercase()}") {
                Aggregate(
                    signal.pattern, signal.direction,
                )
            }
            value.evidence++
            value.weight += weight.coerceAtLeast(0.0)
            value.sources[sourceName] = (value.sources[sourceName] ?: 0) + 1
            signal.focus?.takeUnless(String::isBlank)?.let { value.focus = it }
        }
        activities.forEachIndexed { index, activity ->
            if (source != null && source != activity.source) return@forEachIndexed
            activity.signals.forEach { signal ->
                val weight = if (activity.source == "WRITING") recency(index, activities.size) else {
                    val assistance = when {
                        signal.metric in setOf("FLUENCY", "PRONUNCIATION") -> 1.0
                        "SAMPLE_ANSWER" in activity.assistance -> .60
                        activity.assistance.any { it in setOf("HINT", "TRANSLATION") } -> .80
                        else -> 1.0
                    }
                    val base = round3(
                        recency(index, activities.size) * activity.confidence.coerceIn(0.0, 1.0) *
                            activity.validity.coerceIn(0.0, 1.0) * assistance,
                    )
                    round3(base * signal.confidence.coerceIn(0.0, 1.0))
                }
                add(activity.source, signal, weight)
            }
        }

        // Listening은 활동 창과 별개인 현재 약점 프로필의 표본 수를 근거로 더한다.
        if (source == null || source == "LISTENING") {
            for (element in listeningMetrics) {
                val row = element.jsonObject
                val score = row.number("score") ?: continue
                val direction = if (row.text("weaknessState") in setOf("ACTIVE", "IMPROVING")) "WEAKNESS"
                else if (score >= 75) "STRENGTH" else continue
                val metric = checkNotNull(row.text("metric"))
                val signal =
                    Signal(metric, direction, if (direction == "WEAKNESS") metric.lowercase() else null, 1.0, null)
                repeat(row.count("sampleCount").coerceAtLeast(1)) { add("LISTENING", signal, 1.0) }
            }
        }
        return JsonArray(
            aggregates.values.filter { it.sources.values.any { count -> count >= 2 } || unified(it) }
                .sortedByDescending { it.weight }.take(limit.coerceAtLeast(1)).map { value ->
                    buildJsonObject {
                        put("patternKey", value.pattern)
                        put("direction", value.direction)
                        put("evidenceCount", value.evidence)
                        put("weightedEvidence", round2(value.weight))
                        put("sources", JsonArray(value.sources.keys.map(::JsonPrimitive)))
                        put("unified", unified(value))
                        put("recommendedFocus", value.focus?.let(::JsonPrimitive) ?: JsonNull)
                    }
                },
        )
    }

    fun dashboard(insights: JsonArray): JsonObject = buildJsonObject {
        put(
            "strengths",
            JsonArray(insights.filter { it.jsonObject.text("direction").equals("STRENGTH", true) }.take(10)),
        )
        put(
            "weaknesses",
            JsonArray(insights.filter { !it.jsonObject.text("direction").equals("STRENGTH", true) }.take(10)),
        )
        put(
            "recommendedFocus",
            JsonArray(
                insights.mapNotNull { it.jsonObject.text("recommendedFocus")?.takeUnless(String::isBlank) }
                    .distinct().take(10).map(::JsonPrimitive),
            ),
        )
    }

    private fun writingActivity(row: JsonObject): Activity {
        val data = parseObject(row.text("profileSignalsJson"))
        val signals = listOf(
            "strengthTags", "weaknessTags", "grammarPatterns", "vocabularyPatterns", "naturalnessPatterns",
            "expressionPatterns", "meaningPatterns",
        ).flatMap { key ->
            data.array(key).mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeUnless(String::isBlank) }
                .map { Signal(it, if (key == "strengthTags") "STRENGTH" else "WEAKNESS", null, 1.0, null) }
        }
        return Activity("WRITING", row.time("evaluatedAt"), 1.0, 1.0, emptyList(), deduplicate(signals))
    }

    private fun speakingActivity(row: JsonObject): Activity {
        val data =
            row.text("profileSignalsJson")?.let { Json.parseToJsonElement(it) as? JsonArray } ?: JsonArray(emptyList())
        val signals = data.mapNotNull { element ->
            if (element == JsonNull) return@mapNotNull null
            val signal = element.jsonObject
            val pattern = signal.text("patternKey")?.trim()?.takeUnless(String::isBlank) ?: return@mapNotNull null
            Signal(
                pattern, signal.text("direction")?.trim()?.takeUnless(String::isBlank)?.uppercase() ?: "WEAKNESS",
                signal.text("recommendedFocus"), signal.number("confidence") ?: 0.0, signal.text("metricType"),
            )
        }
        return Activity(
            "SPEAKING", row.time("evaluatedAt"), row.number("evaluationConfidence") ?: 0.0,
            parseObject(row.text("eligibilityJson")).number("validSttTurnRatio") ?: 1.0,
            row.array("assistanceUsage").map { it.jsonPrimitive.content }, deduplicate(signals),
        )
    }

    private fun deduplicate(values: List<Signal>) = values.distinctBy { "${it.direction}:${it.pattern.lowercase()}" }
    private fun parseObject(value: String?) =
        value?.takeUnless(String::isBlank)?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())

    private fun unified(value: Aggregate) = value.sources.size >= 2 && value.evidence >= 3
}
