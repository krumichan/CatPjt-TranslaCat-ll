package jp.co.translacat.languagelearning.features.overview.application

import jp.co.translacat.languagelearning.features.growth.application.GrowthUnitOfWork
import jp.co.translacat.languagelearning.features.leveltest.application.LevelSessionService
import jp.co.translacat.languagelearning.features.listening.application.ListeningReadService
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskType
import jp.co.translacat.languagelearning.features.practice.application.PracticeReadService
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.speaking.application.SpeakingReportService
import jp.co.translacat.languagelearning.features.writing.application.WritingReportService
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import kotlinx.serialization.json.*
import java.time.Duration
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 기능별 공식 읽기 포트를 조합하며 날짜·가중치·표시 규칙의 소유권을 LL에 둔다. */
internal class OverviewService(
    private val settings: SettingsServiceOperations,
    private val writing: WritingReportService,
    private val speaking: SpeakingReportService,
    private val listening: ListeningReadService,
    private val practice: PracticeReadService,
    private val growth: GrowthUnitOfWork,
    private val level: LevelSessionService,
    private val levelDetail: suspend (Long, Long) -> JsonElement,
    private val writingDetail: suspend (Long, Long) -> JsonElement,
) {
    suspend fun dashboard(
        userId: Long, from: LocalDate?, to: LocalDate?, rawSource: String?, task: ListeningTaskType?,
    ): JsonObject {
        // 사용자 시간대와 기존 source/task 조합을 확인하고 화면의 조회 범위를 고정한다.
        val snapshot = settings.userSnapshot(userId)
        val language = snapshot.result.settings.learningLanguage
        if (language.isNullOrBlank() || snapshot.result.settings.originLanguage.isNullOrBlank()) {
            throw LearningBusinessException("LANGUAGE_LEARNING_SETTING_NOT_CONFIGURED", "모국어와 학습 언어를 먼저 설정해 주세요.")
        }
        val today = snapshot.learningDate
        val end = to ?: today
        val start = from ?: end.minusDays(29)
        if (start > end) throw LearningBusinessException("LISTENING_INVALID_STATE", "Dashboard 조회 기간이 올바르지 않습니다.")
        val source = parseSource(rawSource)
        if (task != null && source != null && source != "LISTENING") {
            invalidSource("Listening taskType은 LISTENING 또는 ALL source에서만 사용할 수 있습니다.")
        }
        val includeListening = source == null || source == "LISTENING"

        // 기능별 저장 사실과 Listening 프로필을 읽어 선택 기간의 추세·최근 근거를 계산한다.
        val facts = facts(userId, today, start, end)
        val trend = SourceTrendProjection.project(facts, source, start, end)
        val listeningDashboard = listening.dashboard(userId, start, end, task)
        val listeningProfiles = listeningDashboard.array("metrics")
        val listeningMetrics =
            if (includeListening) listening.metricTrends(userId, language, start, end, task) else JsonArray(emptyList())
        val insightMetrics = listening.dashboard(userId, null, null, null).array("metrics")
        val insights = RecentInsightProjection.dashboard(
            RecentInsightProjection.project(facts.writing, facts.speaking, insightMetrics, source, 30),
        )

        // 기존 base 화면은 선택 기간의 길이를 오늘까지 적용하므로 과거 종료일 조회에서도 같은 의미를 유지한다.
        val days = (ChronoUnit.DAYS.between(start, end) + 1).coerceIn(1, 365)
        val baseSpeaking = speaking.report(userId, today.minusDays(days - 1), today)
        val todayListening =
            listening.report(userId, today, today, null).rows("sets").filter { it.text("learningLanguage") == language }
        val todayReading = practice.report(userId, today, today).rows("sets").filter { it.text("domain") == "READING" }
        val performance = buildJsonObject {
            val writingTrend = SourceTrendProjection.project(facts, "WRITING", start, end)
            val speakingTrend = SourceTrendProjection.project(facts, "SPEAKING", start, end)
            val listeningTrend = SourceTrendProjection.project(facts, "LISTENING", start, end)
            val readingTrend = SourceTrendProjection.project(facts, "READING", start, end)
            put(
                "writing",
                performance(
                    facts.writing.number("weeklyAverageScore"), facts.writing.number("todayCompleted") ?: 0.0,
                    facts.writing.number("todayTotal") ?: 0.0, "ITEM", writingTrend,
                    writingTrend.objects("metrics").size, 5,
                ),
            )
            put(
                "speaking",
                performance(
                    baseSpeaking.objects("summary").number("overallAverage"),
                    baseSpeaking.objects("today").number("completedMinutes") ?: 0.0,
                    baseSpeaking.objects("today").number("goalMinutes") ?: 0.0,
                    "MINUTE", speakingTrend, speakingTrend.objects("metrics").size, 5,
                ),
            )
            val listeningScores = listeningProfiles.mapNotNull { it.jsonObject.number("score") }
            put(
                "listening",
                performance(
                    average(listeningScores), todayListening.sumOf { it.count("completedItemCount") }.toDouble(),
                    todayListening.sumOf { it.count("targetItemCount") }.toDouble(), "ITEM", listeningTrend,
                    listeningScores.size, 10,
                ),
            )
            put(
                "reading",
                performance(
                    latestAverage(readingTrend), todayReading.sumOf { it.count("answeredCount") }.toDouble(),
                    todayReading.sumOf { it.count("questionCount") }.toDouble(), "ITEM", readingTrend,
                    readingTrend.objects("metrics").size, 10,
                ),
            )
        }

        // 특정 Listening 과제를 선택한 경우 기존 지표 선택과 공통 성장 행의 합치기 순서를 유지한다.
        val ability = if (source == "LISTENING" && task != null) DashboardProjection.listeningAbility(
            listeningProfiles,
        ) else DashboardProjection.ability(trend)
        var growthRows = if (source == "LISTENING" && task != null) DashboardProjection.listeningGrowth(
            listeningMetrics,
        ) else DashboardProjection.growth(trend)
        if (source == null && task != null) {
            val merged = linkedMapOf<String, JsonElement>()
            (growthRows + DashboardProjection.listeningGrowth(listeningMetrics)).forEach { element ->
                val row = element.jsonObject
                merged["${row.text("source")}:${row.text("taskType")}:${row.text("metric")}"] = row
            }
            growthRows = JsonArray(merged.values.sortedByDescending { it.jsonObject.number("delta") })
        }

        // 기존 외부 DTO의 필드와 빈 값 표현을 그대로 조립한다.
        return buildJsonObject {
            put("learningLanguage", language)
            put("from", start.toString()); put("to", end.toString()); put("source", source ?: "ALL")
            put("integratedAbility", ability); put("activityPerformance", performance); put("growth", growthRows)
            put(
                "weaknesses",
                weaknesses(
                    insights.array("weaknesses"), if (includeListening) listeningProfiles else JsonArray(emptyList()),
                ),
            )
            put("recommendations", listeningDashboard.array("recommendations"))
            put(
                "trends",
                buildJsonObject {
                    put("sourceMetrics", trend)
                    put(
                        "listeningTasks",
                        if (includeListening) listeningDashboard.array("taskTrends") else JsonArray(emptyList()),
                    )
                    put("listeningMetrics", listeningMetrics)
                },
            )
        }
    }

    suspend fun insights(userId: Long, source: String?, limit: Int): JsonArray {
        // 최근 활동과 현재 Listening 프로필은 원본에서 사용하던 서로 다른 조회 창을 유지한다.
        val today = settings.learningDate(userId)
        val writingFacts = writing.report(userId, today, today.minusDays(29), today)
        val speakingFacts = speaking.report(userId, null, null)
        val language = settings.userSnapshot(userId).result.settings.learningLanguage
        val metrics =
            if (language.isNullOrBlank()) JsonArray(emptyList()) else listening.dashboard(userId, null, null, null)
                .array("metrics")

        // 근거 선별·가중치·최소 표본 판정은 공통 정책에서 한 번 적용한다.
        return RecentInsightProjection.project(writingFacts, speakingFacts, metrics, parseSource(source), limit)
    }

    suspend fun trend(userId: Long, source: String?, from: LocalDate, to: LocalDate): JsonObject {
        val today = settings.learningDate(userId)
        return SourceTrendProjection.project(facts(userId, today, from, to), parseSource(source), from, to)
    }

    suspend fun streak(userId: Long): JsonObject {
        // 원본 streak의 Writing·Speaking 완료일 범위와 날짜 중복 제거를 유지한다.
        val today = settings.learningDate(userId)
        val dates = (writing.report(userId, today, today.minusDays(29), today).array("completedDates") +
            speaking.report(userId, today.minusDays(3650), today).array("completedDates"))
            .map { LocalDate.parse(it.jsonPrimitive.content) }.toSet()

        return HistoryProjection.streak(dates, today)
    }

    suspend fun history(
        userId: Long, source: String?, period: String?, status: String?, task: ListeningTaskType?,
    ): JsonArray {
        // 사용자 학습일을 기준으로 기간을 고정하고 요청한 출처의 읽기 포트만 선택한다.
        val today = settings.learningDate(userId)
        val from = today.minusDays(HistoryProjection.days(period) - 1L)
        val selectedSource = parseSource(source)
        val values = mutableListOf<JsonObject>()

        // 기능별 완료·평가 상태를 바꾸지 않고 공통 이력 형식으로 모은다.
        if (selectedSource == null || selectedSource == "WRITING") {
            values += writing.report(userId, today, from, today).rows("history")
        }
        if (selectedSource == null || selectedSource == "SPEAKING") {
            values += speaking.report(userId, from, today).rows("history")
        }
        if (selectedSource == null || selectedSource == "LISTENING") {
            values += listening.report(userId, from, today, task).rows("history")
        }
        if (selectedSource == null || selectedSource in setOf("READING", "VOCABULARY")) {
            values += practice.report(userId, from, today)
                .rows("sets")
                .filter { selectedSource == null || it.text("domain") == selectedSource }
                .map(HistoryProjection::practice)
        }
        if (selectedSource == null || selectedSource == "LEVEL_TEST") {
            values += level.history(userId).filter { checkNotNull(it.completedDate) in from..today }.map { session ->
                buildJsonObject {
                    put("activityId", "LEVEL_TEST:${session.id}"); put("source", "LEVEL_TEST")
                    put("learningDate", checkNotNull(session.completedDate).toString())
                    put("title", "Language Level Test"); put("topic", session.sessionType.name)
                    put(
                        "durationSeconds",
                        Duration.between(session.startedAt, checkNotNull(session.completedAt)).seconds.coerceAtLeast(0),
                    )
                    put("overallScore", checkNotNull(session.baseLevelScore).toDouble())
                    put("completionStatus", "COMPLETED"); put("evaluationStatus", "COMPLETED")
                }
            }
        }

        // 상태 필터와 동일 날짜 내 원본 출처 순서는 모든 결과를 모은 뒤 적용한다.
        return HistoryProjection.history(values, status)
    }

    suspend fun detail(userId: Long, activityId: String): JsonObject {
        val (source, id) = HistoryProjection.activity(activityId)

        // 기존 LevelTest의 공개 ID 계약과 이후 기능의 음수 ID 계약을 각각 보존한다.
        val databaseId =
            if (source == "LEVEL_TEST") id else jp.co.translacat.languagelearning.shared.identity.LearningPublicId.decode(
                id.toString(),
            )

        // 상세 조회의 사용자 소유권과 결과 공개 범위는 각 기능의 기존 읽기 포트가 확인한다.
        val detail = when (source) {
            "WRITING" -> writingDetail(userId, databaseId)
            "SPEAKING" -> speaking.history(userId, databaseId)
            "LISTENING" -> listening.history(userId, databaseId)
            "READING", "VOCABULARY" -> practice.get(userId, databaseId)
            "LEVEL_TEST" -> levelDetail(userId, databaseId)
            else -> HistoryProjection.notFound()
        }

        return buildJsonObject { put("activityId", activityId); put("source", source); put("detail", detail) }
    }

    private suspend fun facts(userId: Long, today: LocalDate, from: LocalDate, to: LocalDate): OverviewFacts {
        // Growth 저장 사실은 커서로 끝까지 읽어 기존 전체 활동 집계의 의미를 보존한다.
        val activities = growth.read {
            val values = mutableListOf<JsonObject>()
            var cursor = 0L
            while (true) {
                val page = records.activities(userId, "SPEAKING", from, to, cursor, 500)
                if (page.isEmpty()) break
                values += page.map { activity ->
                    buildJsonObject {
                        put("id", activity.id); put("status", activity.status); put(
                        "learningDate", activity.learningDate.toString(),
                    )
                        put("evaluationConfidence", activity.evaluationConfidence)
                        put(
                            "metrics",
                            JsonArray(
                                records.metrics(activity.id).map { metric ->
                                    buildJsonObject {
                                        put("metricType", metric.metricType); put("state", metric.state); put(
                                        "score", metric.score,
                                    )
                                    }
                                },
                            ),
                        )
                    }
                }
                cursor = page.last().id
            }
            values
        }

        // 조회한 Growth 근거와 기능별 읽기 결과를 함께 정책에 전달한다.
        return OverviewFacts(
            writing.report(userId, today, from, to), speaking.report(userId, from, to), activities,
            listening.report(userId, from, to, null), practice.report(userId, from, to),
        )
    }

    private fun performance(
        score: Double?, completed: Double, target: Double, unit: String, trend: JsonObject, evaluated: Int, total: Int,
    ) = buildJsonObject {
        put("recentScore", score?.let(::round2) ?: latestAverage(trend))
        put("coverage", buildJsonObject { put("evaluated", evaluated); put("total", total) })
        put("today", buildJsonObject { put("completed", completed); put("target", target); put("unit", unit) })
        put("sampleCount", trend.getValue("sampleCount")); put("collectingData", trend.getValue("collectingData"))
    }

    private fun latestAverage(trend: JsonObject): Double? = average(
        trend.objects("metrics").values.mapNotNull { values ->
            values.jsonArray.map { it.jsonObject }.maxByOrNull { it.date("date") }?.number("score")
        },
    )

    private fun average(values: List<Double>) = if (values.isEmpty()) null else round2(values.average())
    private fun weaknesses(insights: JsonArray, profiles: JsonArray): JsonArray {
        data class Weakness(
            val key: String, var state: String = "WEAKNESS", var evidence: Int = 0, var score: Double? = null,
            var focus: String? = null, val sources: MutableSet<String> = linkedSetOf(),
        )

        val values = linkedMapOf<String, Weakness>()
        fun merge(key: String?, state: String?, evidence: Int, score: Double?, sources: List<String>, focus: String?) {
            if (key.isNullOrBlank()) return
            val value = values.getOrPut(key.trim().lowercase()) { Weakness(key.trim()) }
            if (state.equals("ACTIVE", true) || !value.state.equals("ACTIVE", true)) value.state = state ?: value.state
            value.evidence += evidence.coerceAtLeast(0)
            if (score != null) value.score = score
            if (!focus.isNullOrBlank()) value.focus = focus
            value.sources += sources
        }

        // 통합 근거 다음에 Listening의 현재 약점 상태를 합치는 원본 우선순위를 유지한다.
        insights.forEach {
            it.jsonObject.let { row ->
                merge(
                    row.text("patternKey"), row.text("direction"), row.count("evidenceCount"), null,
                    row.array("sources").map { it.jsonPrimitive.content }, row.text("recommendedFocus"),
                )
            }
        }
        profiles.map { it.jsonObject }
            .filter { it.text("weaknessState") in setOf("ACTIVE", "IMPROVING") }
            .forEach { row ->
                merge(
                    row.text("metric"), row.text("weaknessState"), row.count("sampleCount"), row.number("score"),
                    listOf("LISTENING"), row.text("metric")?.lowercase(),
                )
            }

        // 근거 수가 큰 약점부터 최대 10개를 기존 응답 필드로 내보낸다.
        return JsonArray(
            values.values.sortedByDescending { it.evidence }.take(10).map { value ->
                buildJsonObject {
                    put("key", value.key); put("state", value.state); put("evidenceCount", value.evidence); put(
                    "recentScore", value.score,
                )
                    put("sources", JsonArray(value.sources.map(::JsonPrimitive))); put(
                    "recommendedFocus", value.focus?.let(::JsonPrimitive) ?: JsonNull,
                )
                }
            },
        )
    }

    companion object {
        fun parseSource(raw: String?): String? {
            if (raw.isNullOrBlank() || raw.equals("ALL", true)) return null
            val source = raw.trim().uppercase()
            return source.takeIf { it in HistoryProjection.sources } ?: invalidSource()
        }

        private fun invalidSource(message: String = "Dashboard source filter가 올바르지 않습니다."): Nothing =
            throw LearningBusinessException("LANGUAGE_LEARNING_DASHBOARD_SOURCE_INVALID", message)
    }
}
