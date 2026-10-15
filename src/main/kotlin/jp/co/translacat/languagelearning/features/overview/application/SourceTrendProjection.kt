package jp.co.translacat.languagelearning.features.overview.application

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/** 기존 SourceSkillTrendQueryService의 표본 선택·최근성·평가 신뢰도 계산을 보존한다. */
internal object SourceTrendProjection {
    fun project(facts: OverviewFacts, source: String?, from: LocalDate, to: LocalDate): JsonObject {
        val scores = linkedMapOf<String, MutableMap<LocalDate, MutableList<Pair<Double, Double>>>>()
        var samples = 0
        val confidences = mutableListOf<Double>()
        fun add(metric: String, date: LocalDate, score: Double?, weight: Double) {
            if (score == null || weight <= 0) return
            scores.getOrPut(metric) { linkedMapOf() }.getOrPut(date) { mutableListOf() }.add(score to weight)
        }

        // Writing과 Speaking은 원본의 서로 다른 학습일·평가 상태·신뢰도 규칙을 유지한다.
        if (source == null || source == "WRITING") {
            val native = facts.writing.rows("evaluations").filter { it.date("learningDate") in from..to }
            val imported = facts.webAi.filter {
                it.text("domain") == "WRITING" && it.date("learningDate") in from..to
            }.map { item ->
                buildJsonObject {
                    put("learningDate", item.getValue("learningDate"))
                    put("evaluatedAt", item.getValue("evaluatedAt"))
                    item.rows("metrics").forEach { metric ->
                        put("${checkNotNull(metric.text("type")).lowercase()}Score", metric.getValue("score"))
                    }
                }
            }
            // 같은 분야의 두 출처를 먼저 합친 뒤 동일한 최근성 가중치를 한 번 적용한다.
            val values = if (imported.isEmpty()) native else (native + imported).sortedByDescending {
                it.text("evaluatedAt") ?: it.text("learningDate")
            }
            samples += values.size
            values.forEachIndexed { index, row ->
                for (metric in listOf("meaning", "grammar", "vocabulary", "naturalness", "expression")) {
                    add(
                        metric.uppercase(), row.date("learningDate"), row.number("${metric}Score"),
                        recency(index, values.size),
                    )
                }
            }
        }
        if (source == null || source == "SPEAKING") {
            val values = facts.speakingGrowth.filter { it.text("status") == "EVALUATED" }
                .sortedWith(
                    compareByDescending<JsonObject> { it.date("learningDate") }.thenByDescending { it.number("id") },
                )
            samples += values.size
            values.forEachIndexed { index, row ->
                row.number("evaluationConfidence")?.let(confidences::add)
                val weight =
                    recency(index, values.size) * (row.number("evaluationConfidence") ?: 0.0).coerceIn(0.0, 1.0)
                row.rows("metrics").filter { it.text("state") == "EVALUATED" }.forEach { metric ->
                    add(
                        checkNotNull(metric.text("metricType")), row.date("learningDate"), metric.number("score"),
                        weight,
                    )
                }
            }
        }

        // Listening은 평가일·지표별 신뢰도를, Practice는 공식 첫 시도의 표본 수를 사용한다.
        if (source == null || source == "LISTENING") {
            val values = facts.listening.rows("evaluations").sortedByDescending { it.time("evaluatedAt") }
            samples += values.map { it.text("attemptId") }.distinct().size
            values.forEachIndexed { index, row ->
                row.number("confidence")?.let { confidences += it.coerceIn(0.0, 1.0) }
                for (metric in row.rows("metrics")) {
                    val type = metric.text("type")?.trim()?.takeUnless(String::isBlank) ?: continue
                    val weight = recency(index, values.size) * (metric.number("confidence") ?: 0.0).coerceIn(0.0, 1.0) *
                        (metric.number("weight") ?: 0.0).coerceAtLeast(0.0)
                    add(type.uppercase(), row.time("evaluatedAt").toLocalDate(), metric.number("score"), weight)
                }
            }
        }
        val domain = when (source) {
            null, "READING" -> "READING"; "VOCABULARY" -> "VOCABULARY"; else -> null
        }
        if (domain != null) {
            val native =
                facts.practice.rows("sets").filter {
                    it.text("domain") == domain && it.text("status") == "COMPLETED"
                }
            val imported = facts.webAi.filter { it.text("domain") == domain && it.date("learningDate") in from..to }.map { item ->
                buildJsonObject {
                    put("learningDate", item.getValue("learningDate"))
                    put("metrics", JsonArray(item.rows("metrics").map { metric ->
                        buildJsonObject {
                            put("skillTag", metric.getValue("type"))
                            put("score", metric.getValue("score"))
                            put("sampleCount", 1)
                        }
                    }))
                }
            }
            val values = if (imported.isEmpty()) native else (native + imported).sortedByDescending {
                it.text("learningDate")
            }
            samples += values.size
            values.forEachIndexed { index, row ->
                row.rows("metrics").forEach { metric ->
                    add(
                        checkNotNull(metric.text("skillTag")), row.date("learningDate"),
                        metric.number("score"),
                        recency(index, values.size) * metric.count("sampleCount").coerceAtLeast(1),
                    )
                }
            }
        }

        // WEB_AI 필터는 표시 출처별 조회다. 공식 분야별 조회와 같은 지표만 포함한다.
        if (source == "WEB_AI") {
            val values = facts.webAi.filter { it.date("learningDate") in from..to }
                .sortedByDescending { it.text("evaluatedAt") }
            samples += values.size
            values.forEachIndexed { index, row ->
                row.rows("metrics").forEach { metric ->
                    add(
                        checkNotNull(metric.text("type")), row.date("learningDate"), metric.number("score"),
                        recency(index, values.size),
                    )
                }
            }
        }

        return buildJsonObject {
            put("source", source ?: "ALL")
            put("sampleCount", samples)
            put("confidence", if (confidences.isEmpty()) 0.0 else round2(confidences.average()))
            put("collectingData", samples < 3)
            put(
                "metrics",
                buildJsonObject {
                    scores.forEach { (metric, dates) ->
                        put(
                            metric,
                            JsonArray(
                                dates.toSortedMap().map { (date, values) ->
                                    buildJsonObject {
                                        put("date", date.toString())
                                        put(
                                            "score",
                                            round2(values.sumOf { it.first * it.second } / values.sumOf { it.second }),
                                        )
                                    }
                                },
                            ),
                        )
                    }
                },
            )
        }
    }
}
