package jp.co.translacat.languagelearning.features.listening.application

import jp.co.translacat.languagelearning.features.listening.domain.model.*
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningProfilePolicy
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.util.*

internal object ListeningProfiles {
    private val targets = mapOf(
        "LISTENING_RECOGNITION" to ("LISTENING" to "DICTATION"), "VOCABULARY" to ("LISTENING" to "DICTATION"),
        "ORTHOGRAPHY" to ("WRITING" to "DAILY_WRITING"), "MEANING" to ("LISTENING" to "INTERPRETATION"),
        "ORIGIN_NATURALNESS" to ("WRITING" to "DAILY_WRITING"),
        "PRONUNCIATION" to ("LISTENING" to "REPEAT_AFTER_AUDIO"),
        "FLUENCY" to ("LISTENING" to "REPEAT_AFTER_AUDIO"), "SPOKEN_EXPRESSION" to ("SPEAKING" to "FREE_CONVERSATION"),
        "INTERACTION" to ("SPEAKING" to "ROLE_PLAY"), "WRITTEN_EXPRESSION" to ("WRITING" to "DAILY_WRITING"),
    )

    fun record(
        transaction: ListeningTransaction, set: ListeningSetState, attempt: ListeningAttemptState,
        response: ListeningResponseState, result: ListeningTaskResult, profileVersion: String,
    ) = with(transaction) {
        val reference = "${response.id}:${response.evaluationHistory.size}:listening-evaluation"
        val history = metricHistory(set.userId, set.learningLanguage)

        // 평가 가능한 공식 결과만 프로필에 적용하되 제외된 원본 신호도 이력에 남긴다.
        for (signal in result.profileSignals) {
            if (signal.metric !in ListeningProfilePolicy.metrics || history.any { it.referenceEvaluationId == reference && it.metric == signal.metric }) continue
            val eligible =
                result.profileEligible && attempt.purpose == "OFFICIAL" && !attempt.answerRevealed && result.evaluable &&
                    signal.confidence >= .70 && signal.evidenceWeight > 0
            val rank = minOf(30, history.count { it.profileApplied && it.metric == signal.metric } + 1)
            saveMetricHistory(
                ListeningMetricHistoryState(
                    allocateId(), set.userId, set.learningLanguage, response.taskType,
                    signal.metric, signal.score, signal.confidence, ListeningProfilePolicy.recencyWeight(rank),
                    ListeningProfilePolicy.assistanceWeight(result.assistanceLevel), signal.evidenceWeight,
                    if (eligible) ListeningProfilePolicy.finalWeight(
                        rank, signal.confidence, result.assistanceLevel, signal.evidenceWeight,
                    ) else 0.0,
                    result.assistanceLevel, "LISTENING_ATTEMPT:${attempt.id}", reference, attempt.purpose == "OFFICIAL",
                    attempt.purpose == "PRACTICE", eligible, "listening-evaluation", profileVersion, nowUtc.toString(),
                ),
            )
        }
    }

    fun independence(
        transaction: ListeningTransaction, set: ListeningSetState, attempt: ListeningAttemptState, score: Double,
    ): Unit = with(transaction) {
        val reference = "LISTENING_ATTEMPT:${attempt.id}:INDEPENDENCE"
        val history = metricHistory(set.userId, set.learningLanguage)
        if (history.any { it.referenceEvaluationId == reference && it.metric == "LISTENING_INDEPENDENCE" }) return
        val rank = minOf(30, history.count { it.profileApplied && it.metric == "LISTENING_INDEPENDENCE" } + 1)
        saveMetricHistory(
            ListeningMetricHistoryState(
                allocateId(), set.userId, set.learningLanguage, null,
                "LISTENING_INDEPENDENCE", score, 1.0, ListeningProfilePolicy.recencyWeight(rank), 1.0, 1.0,
                ListeningProfilePolicy.finalWeight(rank, 1.0, "INDEPENDENT", 1.0), "INDEPENDENT",
                "LISTENING_ATTEMPT:${attempt.id}", reference, true, false, true, "listening-independence",
                "listening-profile", nowUtc.toString(),
            ),
        )
    }

    fun profiles(
        history: List<ListeningMetricHistoryState>, task: ListeningTaskType? = null,
    ): List<ListeningMetricProfile> =
        ListeningProfilePolicy.metrics.map { metric ->
            // 기존 query의 최근 30행 제한을 활동 중복 제거보다 먼저 적용한다.
            val signals =
                history.filter { it.profileApplied && it.metric == metric && (task == null || it.taskType == task) }
                    .take(30)
                    .map {
                        ListeningProfilePolicy.Signal(
                            it.referenceActivityId, it.rawScore, it.confidence, it.assistanceLevel,
                            it.evidenceWeight, it.official, it.practice, !it.profileApplied,
                            LocalDateTime.parse(it.createdAt),
                        )
                    }
            val aggregate = ListeningProfilePolicy.aggregate(signals)
            val weakness = ListeningProfilePolicy.weakness(signals)
            val growth = ListeningProfilePolicy.growth(signals, weakness.state in setOf("ACTIVE", "IMPROVING"))
            ListeningMetricProfile(
                metric, aggregate.score, aggregate.sampleCount, aggregate.confidence, weakness.state, growth.active,
                growth.delta,
            )
        }

    fun recalculate(transaction: ListeningTransaction, userId: Long, language: String) = with(transaction) {
        val history = metricHistory(userId, language)

        // 같은 활동의 최신 근거 하나만 최근 30개 가중치에 포함하고 오래된 중복은 0으로 둔다.
        for (metric in ListeningProfilePolicy.metrics) {
            val activities = mutableSetOf<String>()
            var rank = 0
            history.filter { it.profileApplied && it.metric == metric }.forEach { row ->
                val usable = activities.add(row.referenceActivityId) && rank < 30
                if (usable) rank++
                saveMetricHistory(
                    row.copy(
                        recencyWeight = if (usable) ListeningProfilePolicy.recencyWeight(rank) else 0.0,
                        finalWeight = if (usable) ListeningProfilePolicy.finalWeight(
                            rank, row.confidence, row.assistanceLevel, row.evidenceWeight,
                        ) else 0.0,
                    ),
                )
            }
        }
        val weak = profiles(history).filter { it.weaknessState == "ACTIVE" && it.metric in targets }
            .sortedBy { it.score ?: 101.0 }
            .take(2)
        val selected = weak.map { it.metric }.toSet()
        val existing = recommendations(userId, language)
        existing.filter { it.status == "ACTIVE" }.forEach { row ->
            val status = when {
                LocalDateTime.parse(
                    row.expiresAt,
                ) < nowUtc -> "EXPIRED"; row.targetMetric !in selected -> "RESOLVED"; else -> row.status
            }
            if (status != row.status) saveRecommendation(row.copy(status = status))
        }

        // 추천 결정은 서버 규칙으로 고정한다. 해제한 추천은 재활성화하지 않는다.
        weak.forEachIndexed { index, profile ->
            val prior =
                existing.singleOrNull { it.targetMetric == profile.metric && it.calculationVersion == "listening-recommendation" }
            if (prior?.status == "DISMISSED") return@forEachIndexed
            val target = targets.getValue(profile.metric)
            val reason = "${profile.metric} 최근 근거가 약점 기준(<65)에 해당합니다."
            val value = prior?.copy(
                status = "ACTIVE", priority = index + 1, expiresAt = nowUtc.plusDays(7).toString(),
                reason = if (prior.explanationVersion == null) reason else prior.reason,
            )
                ?: ListeningRecommendationState(
                    allocateId(), userId, language, profile.metric, target.first, target.second,
                    reason, index + 1, nowUtc.plusDays(7).toString(), nowUtc.toString(),
                )
            saveRecommendation(value)
            val explanationKey = "listening:recommendation:${value.id}:explanation:listening-recommendation"
            if (jobForKey(userId, explanationKey) != null) return@forEachIndexed
            enqueue(
                userId, value.id, "EXPLANATION", explanationKey,
                buildJsonObject {
                    put("learningLanguage", language)
                    put("evidenceCount", profile.sampleCount)
                    put("recentAverage", profile.score ?: 0.0)
                    put("sources", buildJsonArray { add("LISTENING") })
                },
            )
        }
    }
}

internal class ListeningProfileWorker(private val work: ListeningUnitOfWork) {
    suspend fun process(pending: ListeningJob) {
        require(pending.type == "PROFILE")
        work.write(pending.userId) {
            val claim =
                claim(pending.userId, pending.id, UUID.randomUUID().toString(), nowUtc.plusMinutes(5)) ?: return@write
            val session = checkNotNull(session(claim.userId, claim.aggregateId))
            val set = checkNotNull(set(claim.userId, session.setId))

            // 순수 DB 재계산과 outbox 완료를 같은 잠금·transaction에서 실행한다.
            ListeningProfiles.recalculate(this, claim.userId, set.learningLanguage)
            check(finish(claim))
        }
    }
}
