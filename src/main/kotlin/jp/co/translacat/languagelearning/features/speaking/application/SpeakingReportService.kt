package jp.co.translacat.languagelearning.features.speaking.application

import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingResultKind
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingResultRecord
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingSessionRecord
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingSessionStatus
import jp.co.translacat.languagelearning.features.writing.application.WritingReportQueries
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.LocalDateTime

/** 공통 화면은 원본 평가 사실과 기존 집계 결과를 읽으며 Core 저장소를 다시 조회하지 않는다. */
internal class SpeakingReportService(
    private val work: SpeakingUnitOfWork,
    private val settings: SettingsServiceOperations,
    private val reads: SpeakingReadService,
    private val evaluationView: suspend (Long, Long) -> JsonElement,
    private val writing: WritingReportQueries,
) {
    suspend fun report(userId: Long, from: LocalDate?, to: LocalDate?): JsonObject {
        val current = settings.userSnapshot(userId)
        val today = current.learningDate
        val start = from ?: today.minusDays(29)
        val end = to ?: today
        require(!end.isBefore(start))

        // MySQL DATE 범위 전체에서 원본 평가를 읽는다. 기간은 화면 통계에만 적용한다.
        return work.read {
            val all = records.sessions(userId, firstDate, lastDate)
            val selected = all.filter { it.learningDate in start..end }
            val todayCompleted = all.filter { it.learningDate == today && completed(it) }
            val facts = all.mapNotNull { session -> evaluationFact(session) }
            val ranged = facts.filter {
                it.session.learningDate in start..end && it.result.response.number(
                    "overallScore",
                ) != null
            }
            val minutes = round(todayCompleted.sumOf { it.totalDurationSeconds } / 60.0)
            val goal = current.result.settings.dailySpeakingGoalMinutes
            val metrics = ranged.flatMap { it.result.response.rows("metrics") }.map { it.jsonObject }

            buildJsonObject {
                put(
                    "today",
                    buildJsonObject {
                        put("completedSessions", todayCompleted.size)
                        put("completedMinutes", minutes)
                        put("goalMinutes", goal)
                        put(
                            "status",
                            if (minutes >= goal) "COMPLETED" else if (minutes > 0) "IN_PROGRESS" else "NOT_STARTED",
                        )
                    },
                )
                put(
                    "summary",
                    buildJsonObject {
                        put("sessions", selected.count(::completed))
                        put(
                            "totalMinutes",
                            round(
                                selected.filter { it.status != SpeakingSessionStatus.EXPIRED }
                                    .sumOf { it.totalDurationSeconds } / 60.0,
                            ),
                        )
                        put("overallAverage", average(ranged.mapNotNull { it.result.response.number("overallScore") }))
                        for ((key, type) in listOf(
                            "fluencyAverage" to "FLUENCY", "pronunciationAverage" to "PRONUNCIATION",
                            "interactionAverage" to "INTERACTION",
                        )) {
                            put(
                                key,
                                average(
                                    metrics.filter { it.text("type") == type && it.text("state") == "EVALUATED" }
                                        .mapNotNull { it.number("score") },
                                ),
                            )
                        }
                        put("collectingData", ranged.size < 3)
                    },
                )
                put(
                    "completedDates",
                    JsonArray(
                        selected.filter(::completed)
                            .map { it.learningDate }
                            .distinct()
                            .map { JsonPrimitive(it.toString()) },
                    ),
                )
                put(
                    "history",
                    JsonArray(
                        selected.map { session ->
                            val result = records.result(session.id, 0)
                                ?.takeIf { it.resultKind == SpeakingResultKind.SCORED_EVALUATION }
                            buildJsonObject {
                                put("activityId", "SPEAKING:${LearningPublicId.encode(session.id)}")
                                put("source", "SPEAKING")
                                put("learningDate", session.learningDate.toString())
                                put("title", session.snapshot.topicTitle)
                                put("topic", session.snapshot.topicCategory)
                                put("durationSeconds", session.totalDurationSeconds)
                                put("overallScore", result?.response?.get("overallScore") ?: JsonNull)
                                put("completionStatus", session.status.name)
                                put(
                                    "evaluationStatus",
                                    if (session.snapshot.resultKind == SpeakingResultKind.SESSION_COACHING)
                                        records.job(session.id, 0)?.status?.name
                                            ?: "NOT_REQUESTED" else session.evaluationStatus.name,
                                )
                            }
                        },
                    ),
                )

                // 최근 통합 근거는 기간 제한 없이 원래 평가 시각·신뢰도·도움·validity를 전달한다.
                put(
                    "evaluations",
                    JsonArray(
                        facts.sortedByDescending { it.result.updatedAt }.map { fact ->
                            buildJsonObject {
                                put("sessionId", LearningPublicId.encode(fact.session.id))
                                put("learningDate", fact.session.learningDate.toString())
                                put("evaluatedAt", fact.result.updatedAt.toString())
                                put("status", fact.result.status)
                                put("overallScore", fact.result.response["overallScore"] ?: JsonNull)
                                put("evaluationConfidence", fact.result.response["evaluationConfidence"] ?: JsonNull)
                                put("profileSignalsJson", fact.result.response["profileSignals"]?.toString() ?: "[]")
                                put("eligibilityJson", fact.result.response["eligibility"]?.toString() ?: "{}")
                                put("assistanceUsage", JsonArray(fact.assistance.map(::JsonPrimitive)))
                                put("metrics", fact.result.response["metrics"] ?: JsonArray(emptyList()))
                            }
                        },
                    ),
                )
            }
        }
    }

    suspend fun history(userId: Long, sessionId: Long): JsonObject {
        // 원본 이력 조회는 상태를 변경하지 않으며 미평가 결과는 오류 대신 null로 반환한다.
        val detail = reads.historyPayload(userId, sessionId)
        val hasEvaluation = work.read {
            records.result(sessionId, 0)?.resultKind == SpeakingResultKind.SCORED_EVALUATION
        }
        val evaluation = if (hasEvaluation) evaluationView(userId, sessionId) else JsonNull
        return buildJsonObject {
            put("session", detail.getValue("session"))
            put("turns", detail.getValue("turns"))
            put("evaluation", evaluation)
            put("coachingResult", detail.getValue("coachingResult"))
        }
    }

    suspend fun recommendedFocus(userId: Long, limit: Int = 10): List<String> {
        // 원본은 Writing과 Speaking을 합쳐 최신 30개를 고른 뒤 Speaking만 집계한다.
        val writingTimes = writing.read(userId).evaluations
            .filter { it.context == "DAILY" && it.status == "SUCCESS" }.map { checkNotNull(it.evaluatedAt) }
        val speaking = work.read {
            records.sessions(userId, firstDate, lastDate).mapNotNull { session -> evaluationFact(session) }
                .filter {
                    it.result.status.equals("EVALUATED", true) && it.result.response.number(
                        "overallScore",
                    ) != null
                }
                .sortedByDescending { it.result.updatedAt }.map { fact ->
                    SpeakingFocusActivity(
                        fact.result.updatedAt, fact.result.response.number("evaluationConfidence") ?: 0.0,
                        (fact.result.response["eligibility"] as? JsonObject)?.number("validSttTurnRatio") ?: 1.0,
                        fact.assistance, fact.result.response.rows("profileSignals").map { it.jsonObject },
                    )
                }
        }
        return SpeakingRecentFocus.recommended(writingTimes, speaking, limit)
    }

    private fun SpeakingTransaction.evaluationFact(session: SpeakingSessionRecord): EvaluationFact? {
        val result = records.result(session.id, 0)?.takeIf { it.resultKind == SpeakingResultKind.SCORED_EVALUATION }
            ?: return null
        return EvaluationFact(
            session, result,
            records.turns(session.id).flatMap { it.content.assistanceUsage }.map { it.name }.distinct(),
        )
    }

    private data class EvaluationFact(
        val session: SpeakingSessionRecord, val result: SpeakingResultRecord, val assistance: List<String>,
    )

    private fun completed(value: SpeakingSessionRecord) = !value.active && value.status != SpeakingSessionStatus.EXPIRED
    private fun average(values: List<Double>): Double? = if (values.isEmpty()) null else round(values.average())
    private fun round(value: Double) = Math.round(value * 100.0) / 100.0
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.rows(key: String) = (get(key) as? JsonArray)?.toList().orEmpty()
    private val firstDate = LocalDate.of(1000, 1, 1)
    private val lastDate = LocalDate.of(9999, 12, 31)
}

internal data class SpeakingFocusActivity(
    val evaluatedAt: LocalDateTime, val confidence: Double, val validity: Double,
    val assistance: List<String>, val signals: List<JsonObject>,
)

/** 기존 RecentLearningProfileInsight의 Speaking 전용 추천 계산을 그대로 보존한다. */
internal object SpeakingRecentFocus {
    fun recommended(
        writingTimes: List<LocalDateTime>, speaking: List<SpeakingFocusActivity>, limit: Int,
    ): List<String> {
        // 동시각은 Writing 먼저, 이후 기존 Speaking 순서로 안정 정렬한다.
        val activities = (writingTimes.map { it to null } + speaking.map { it.evaluatedAt to it })
            .sortedByDescending { it.first }.take(30)

        data class Aggregate(var count: Int = 0, var weight: Double = 0.0, var focus: String? = null)

        val values = linkedMapOf<String, Aggregate>()

        // 같은 활동 내 중복 pattern은 최초 근거만 사용하고 마지막 비어 있지 않은 focus를 유지한다.
        activities.forEachIndexed { index, (_, activity) ->
            if (activity == null) return@forEachIndexed
            val recency = if (activities.size <= 1) 1.0 else round(1.0 - .5 * index / (activities.size - 1))
            val seen = mutableSetOf<String>()
            for (signal in activity.signals) {
                val pattern = signal.text("patternKey")?.trim()?.takeUnless(String::isBlank) ?: continue
                val direction = signal.text("direction")?.trim()?.takeUnless(String::isBlank)?.uppercase() ?: "WEAKNESS"
                val key = "$direction:${pattern.lowercase()}"
                if (!seen.add(key)) continue
                val assistance = when {
                    signal.text("metricType") in setOf("FLUENCY", "PRONUNCIATION") -> 1.0
                    "SAMPLE_ANSWER" in activity.assistance -> .60
                    activity.assistance.any { it in setOf("HINT", "TRANSLATION") } -> .80
                    else -> 1.0
                }
                val base = round(
                    recency * activity.confidence.coerceIn(0.0, 1.0) * activity.validity.coerceIn(
                        0.0, 1.0
                    ) * assistance,
                )
                val weight = round(
                    base * checkNotNull((signal["confidence"] as? JsonPrimitive)?.doubleOrNull).coerceIn(0.0, 1.0),
                )
                val value = values.getOrPut(key) { Aggregate() }
                value.count++
                value.weight += weight.coerceAtLeast(0.0)
                signal.text("recommendedFocus")?.takeUnless(String::isBlank)?.let { value.focus = it }
            }
        }
        return values.values.filter { it.count >= 2 }.sortedByDescending { it.weight }.take(30)
            .mapNotNull { it.focus }.distinct().take(limit.coerceAtLeast(1))
    }

    private fun round(value: Double) = Math.round(value * 1000.0) / 1000.0
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}
