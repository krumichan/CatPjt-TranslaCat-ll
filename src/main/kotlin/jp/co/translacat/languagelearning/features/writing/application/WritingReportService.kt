package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.YearMonth

/** 기존 Writing 대시보드 계산과 이력 상태 판정을 LL 데이터에 적용한다. */
internal class WritingReportService(private val queries: WritingReportQueries) {
    suspend fun report(userId: Long, today: LocalDate, from: LocalDate, to: LocalDate): JsonObject {
        require(!to.isBefore(from))
        val data = queries.read(userId)
        val daily = data.evaluations.filter { it.context == "DAILY" && it.status == "SUCCESS" }
        val month = YearMonth.from(today)
        val monthly = daily.filter { it.learningDate in month.atDay(1)..month.atEndOfMonth() }
        val metricNames = listOf("overall", "meaning", "grammar", "vocabulary", "naturalness", "expression")
        fun average(values: List<WritingReportEvaluation>, index: Int): Double {
            val scores = values.mapNotNull { it.scores[index] }
            return round(if (scores.isEmpty()) 0.0 else scores.average())
        }

        fun overall(values: List<WritingReportEvaluation>): JsonElement =
            if (values.isEmpty()) JsonNull else JsonPrimitive(average(values, 0))

        // 날짜 범위별 통계와 월간 강점·약점의 기존 동점 순서를 유지한다.
        val monthMetrics = (1..5).map { it to average(monthly, it) }
        return buildJsonObject {
            put("started", data.sets.isNotEmpty())
            put("todayCompleted", data.sets.filter { it.date == today }.sumOf { it.answered })
            put("todayTotal", data.sets.filter { it.date == today }.sumOf { it.count })
            put("totalStudySentenceCount", data.totalAnswered)
            put("weeklyAverageScore", overall(daily.filter { it.learningDate in today.minusDays(6)..today }))
            put("monthlyAverageScore", overall(daily.filter { it.learningDate in today.withDayOfMonth(1)..today }))
            put(
                "metricTrend",
                JsonArray(
                    daily.filter { it.learningDate in today.minusDays(29)..today }
                        .groupBy { it.learningDate }.toSortedMap().map { (date, rows) ->
                            buildJsonObject {
                                put("date", date.toString())
                                metricNames.forEachIndexed { index, name -> put(name, average(rows, index)) }
                            }
                        },
                ),
            )
            put(
                "recentLearningHistory",
                JsonArray(
                    data.sets.take(10).map { set ->
                        buildJsonObject {
                            put("learningDate", set.date.toString())
                            put("sentenceCount", set.count)
                            put("status", set.status)
                            put("averageScore", overall(daily.filter { it.learningDate == set.date }))
                        }
                    },
                ),
            )
            put(
                "monthlyReport",
                buildJsonObject {
                    put("month", month.toString())
                    put("evaluatedSentenceCount", monthly.size)
                    put("overallAverage", overall(monthly))
                    put(
                        "strongestMetric",
                        if (monthly.isEmpty()) JsonNull else
                            JsonPrimitive(metricNames[monthMetrics.maxBy { it.second }.first].uppercase()),
                    )
                    put(
                        "weakestMetric",
                        if (monthly.isEmpty()) JsonNull else
                            JsonPrimitive(metricNames[monthMetrics.minBy { it.second }.first].uppercase()),
                    )
                },
            )
            put(
                "completedDates",
                JsonArray(
                    data.sets.filter { it.status == "COMPLETED" }
                        .map { it.date }.distinct().map { JsonPrimitive(it.toString()) },
                ),
            )

            // 이력의 공식 점수는 학습 당일 DAILY 평가만 사용하고 옛 Core ID와 분리한다.
            put(
                "history",
                JsonArray(
                    data.sets.filter { it.date in from..to }.map { set ->
                        val evaluations = data.evaluations.filter {
                            it.setId == set.id && it.attemptDate == set.date && it.context == "DAILY"
                        }
                        val success = evaluations.filter { it.status == "SUCCESS" }.mapNotNull { it.scores[0] }
                        buildJsonObject {
                            put("activityId", "WRITING:${LearningPublicId.encode(set.id)}")
                            put("source", "WRITING")
                            put("learningDate", set.date.toString())
                            put("title", "Daily Writing · ${set.type}")
                            put("topic", set.type)
                            put("durationSeconds", 0)
                            put("overallScore", if (success.isEmpty()) JsonNull else JsonPrimitive(success.average()))
                            put("completionStatus", set.status)
                            put(
                                "evaluationStatus",
                                when {
                                    evaluations.isEmpty() -> "NOT_STARTED"
                                    evaluations.any { it.status == "PENDING" } -> "PENDING"
                                    evaluations.any { it.status == "FAILED" } -> "FAILED"
                                    evaluations.all { it.status == "SUCCESS" } -> "SUCCESS"
                                    else -> "IN_PROGRESS"
                                },
                            )
                        }
                    },
                ),
            )

            // 공통 기능 집계는 전환 중인 다른 기능과 시간순으로 합쳐야 하므로 원본 평가 사실도 제공한다.
            put(
                "evaluations",
                JsonArray(
                    daily.map { row ->
                        buildJsonObject {
                            put("learningDate", row.learningDate.toString())
                            put("evaluatedAt", checkNotNull(row.evaluatedAt).toString())
                            metricNames.forEachIndexed { index, name ->
                                put("${name}Score", row.scores[index]?.let(::JsonPrimitive) ?: JsonNull)
                            }
                            put("profileSignalsJson", row.profileSignalsJson?.let(::JsonPrimitive) ?: JsonNull)
                        }
                    },
                ),
            )
        }
    }

    private fun round(value: Double) = Math.round(value * 100.0) / 100.0
}
