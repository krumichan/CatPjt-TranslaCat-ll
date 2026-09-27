package jp.co.translacat.languagelearning.features.overview.application

import kotlinx.serialization.json.*
import java.time.LocalDate

/** 기존 BE DashboardProjectionPolicy의 표시·집계 임계값을 유지한다. */
internal object DashboardProjection {
    fun ability(trend: JsonObject): JsonObject {
        val collecting = trend.getValue("collectingData").jsonPrimitive.boolean
        val metrics = trend.getValue("metrics").jsonObject.entries.sortedBy { it.key }.map { (name, raw) ->
            val points = raw.jsonArray.map { it.jsonObject }
            val latest = points.maxByOrNull { it.text("date")!! }
            val insufficient = collecting || points.size < 3
            buildJsonObject {
                put("metric", name)
                put("score", latest?.number("score")?.let(::round))
                put("sampleCount", points.size)
                put("confidence", if (insufficient) "DATA_COLLECTING" else confidence(points.size))
                put("collectingData", insufficient)
            }
        }
        return integrated(metrics)
    }

    fun listeningAbility(profiles: JsonArray): JsonObject = integrated(
        profiles.map { it.jsonObject }
            .filter { it.number("score") != null }.map { profile ->
                buildJsonObject {
                    put("metric", profile.getValue("metric"))
                    put("score", round(checkNotNull(profile.number("score"))))
                    put("sampleCount", profile.getValue("sampleCount"))
                    put("confidence", profile.getValue("confidence"))
                    put("collectingData", profile.getValue("sampleCount").jsonPrimitive.int < 3)
                }
            }.sortedBy { it.text("metric") },
    )

    fun growth(trend: JsonObject): JsonArray = JsonArray(
        trend.getValue("metrics").jsonObject.mapNotNull { (metric, raw) ->
            window(
                raw.jsonArray.map { value ->
                    value.jsonObject.let {
                        Point(LocalDate.parse(checkNotNull(it.text("date"))), checkNotNull(it.number("score")), 1)
                    }
                },
            )?.let { growth(metric, checkNotNull(trend.text("source")), null, it) }
        }.sortedByDescending { it.number("delta") },
    )

    fun listeningGrowth(trends: JsonArray): JsonArray {
        // 기존 Task/metric별 날짜 평균과 sample 가중치를 그대로 사용한다.
        val grouped = trends.map { it.jsonObject }.filter { it.number("averageScore") != null }
            .groupBy { it.text("taskType") to checkNotNull(it.text("metric")) }
        return JsonArray(
            grouped.mapNotNull { (key, rows) ->
                window(
                    rows.map {
                        Point(
                            LocalDate.parse(checkNotNull(it.text("date"))), checkNotNull(it.number("averageScore")),
                            it.getValue("sampleCount").jsonPrimitive.int.coerceAtLeast(1),
                        )
                    },
                )
                    ?.let { growth(key.second, "LISTENING", key.first, it) }
            }.sortedByDescending { it.number("delta") },
        )
    }

    private fun integrated(metrics: List<JsonObject>): JsonObject {
        val measured = metrics.filter { it.number("score") != null }
        val scores = measured.map { checkNotNull(it.number("score")) }
        val grouped = measured.groupBy { group(checkNotNull(it.text("metric"))) }
        return buildJsonObject {
            put("overall", scores.takeUnless(List<Double>::isEmpty)?.average()?.let(::round))
            put(
                "confidence",
                when {
                    measured.isEmpty() -> "DATA_COLLECTING"
                    measured.all {
                        !it.getValue(
                            "collectingData",
                        ).jsonPrimitive.boolean
                    } && measured.size >= 8 -> "HIGH"

                    measured.size >= 5 -> "MEDIUM"
                    else -> "LOW"
                },
            )
            put("measuredMetricCount", measured.size)
            put("totalMetricCount", 10)
            put(
                "groups",
                JsonArray(
                    grouped.map { (key, rows) ->
                        buildJsonObject {
                            put("group", key)
                            put("score", round(rows.map { checkNotNull(it.number("score")) }.average()))
                            put("measuredMetricCount", rows.size)
                        }
                    },
                ),
            )
            put("metrics", JsonArray(metrics))
        }
    }

    private fun group(metric: String): String {
        val value = metric.uppercase()
        return when {
            "LISTENING" in value || value in setOf("MEANING", "VOCABULARY") -> "COMPREHENSION"
            listOf("PRONUNCIATION", "FLUENCY", "INTERACTION").any { it in value } -> "SPEECH"
            "EXPRESSION" in value -> "EXPRESSION"
            else -> "ACCURACY"
        }
    }

    private fun window(points: List<Point>): Window? {
        // 최근 최대5개 날짜와 그 이전 최대5개 날짜를 분리하고 양쪽 최소3개 근거를 확인한다.
        val ordered = points.sortedByDescending { it.date }
        if (ordered.size < 6) return null
        val recent = ordered.take(5)
        val previous = ordered.drop(recent.size).take(5)
        val recentCount = recent.sumOf { it.samples }
        val previousCount = previous.sumOf { it.samples }
        if (recentCount < 3 || previousCount < 3) return null
        val recentAverage = recent.sumOf { it.score * it.samples } / recentCount
        val previousAverage = previous.sumOf { it.score * it.samples } / previousCount
        val delta = round(recentAverage - previousAverage)
        return if (delta >= 5) Window(
            round(previousAverage), round(recentAverage), delta, previousCount, recentCount,
        ) else null
    }

    private fun growth(metric: String, source: String, task: String?, value: Window) = buildJsonObject {
        put("metric", metric)
        put("source", source)
        put("taskType", task)
        put("previousAverage", value.previous)
        put("recentAverage", value.recent)
        put("delta", value.delta)
        put("previousSampleCount", value.previousSamples)
        put("recentSampleCount", value.recentSamples)
    }

    private fun confidence(count: Int) = if (count >= 10) "HIGH" else if (count >= 5) "MEDIUM" else "LOW"
    private fun round(value: Double) = Math.round(value * 100.0) / 100.0
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.doubleOrNull
    private data class Point(val date: LocalDate, val score: Double, val samples: Int)
    private data class Window(
        val previous: Double, val recent: Double, val delta: Double, val previousSamples: Int, val recentSamples: Int,
    )
}
