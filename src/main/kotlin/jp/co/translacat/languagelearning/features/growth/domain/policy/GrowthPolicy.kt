package jp.co.translacat.languagelearning.features.growth.domain.policy

import jp.co.translacat.languagelearning.features.growth.domain.model.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/** 기존 Core 수식/기준을 이식한다. 점수·난이도·AI 검증 정책을 새로 정의하지 않는다. */
internal object GrowthPolicy {
    val sources = setOf("WRITING", "SPEAKING", "LISTENING", "READING", "VOCABULARY", "LEVEL_TEST")
    val signalTypes = setOf("GRAMMAR_WEAKNESS", "ERROR_PATTERN", "STRENGTH", "WEAKNESS", "RECOMMENDED_FOCUS", "VOCABULARY_CANDIDATE")
    val statuses = setOf("COMPLETED", "EVALUATING", "EVALUATED", "EVALUATION_FAILED", "INSUFFICIENT_EVIDENCE")
    val metricTypes = setOf("MEANING", "GRAMMAR", "VOCABULARY", "NATURALNESS", "EXPRESSIVENESS", "FLUENCY", "PRONUNCIATION", "INTERACTION")

    fun prepare(profile: GrowthProfile, date: LocalDate, now: LocalDateTime): GrowthProfile {
        if (profile.state != "CALIBRATING") return profile
        val started = profile.calibrationStartedDate ?: date
        return if (!date.isBefore(started.plusDays(7))) profile.copy(
            state = "ACTIVE", calibrationStartedDate = started, calibrationCompletedDate = date, updatedAt = now,
        ) else profile.copy(calibrationStartedDate = started, updatedAt = now)
    }

    fun applyScores(profile: GrowthProfile, change: GrowthChange.WritingScored, now: LocalDateTime): GrowthProfile {
        require(change.scores.size == 5 && change.scores.all { it.isFinite() && it in 0.0..100.0 })
        require(change.difficulty in setOf("REVIEW", "NORMAL", "CHALLENGE"))
        val ready = prepare(profile, change.learningDate, now)
        val weight = weight(ready)
        val previous = listOf(ready.meaningScore, ready.grammarScore, ready.vocabularyScore, ready.naturalnessScore, ready.expressionScore)
        val oldAverage = previous.filterNotNull().average().let { if (it.isNaN()) 0.0 else it }
        val blended = previous.zip(change.scores).map { (old, new) -> blend(old, new, weight) }
        val difference = blended.average() - oldAverage
        val count = Math.addExact(ready.evaluationCount, 1)
        val score = change.scores.average()
        return ready.copy(
            meaningScore = blended[0], grammarScore = blended[1], vocabularyScore = blended[2],
            naturalnessScore = blended[3], expressionScore = blended[4],
            reviewPerformance = if (change.difficulty == "REVIEW") blend(ready.reviewPerformance, score, weight) else ready.reviewPerformance,
            normalPerformance = if (change.difficulty == "NORMAL") blend(ready.normalPerformance, score, weight) else ready.normalPerformance,
            challengePerformance = if (change.difficulty == "CHALLENGE") blend(ready.challengePerformance, score, weight) else ready.challengePerformance,
            evaluationCount = count, confidence = minOf(1.0, count / 20.0),
            trend = if (difference > 3) "improving" else if (difference < -3) "declining" else "stable",
            updatedAt = now,
        )
    }

    fun weight(profile: GrowthProfile): Double = if (profile.state == "CALIBRATING") 0.50 else 0.30
    fun blend(old: Double?, score: Double, weight: Double): Double = round(old?.let { it * (1 - weight) + score * weight } ?: score)
    fun round(value: Double): Double = Math.round(value * 100.0) / 100.0
    fun signalKey(raw: String): String = raw.trim().take(300)
    fun direction(raw: String?): String = raw?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT) ?: "WEAKNESS"

    fun selected(value: KeywordMastery, date: LocalDate, now: LocalDateTime) = value.copy(
        lastSelectedDate = date, selectedCount = Math.addExact(value.selectedCount, 1), updatedAt = now,
    )

    fun scored(value: KeywordMastery, score: Double, weight: Double, now: LocalDateTime) = value.copy(
        score = if (value.evaluationCount == 0) round(score) else blend(value.score, score, weight),
        evaluationCount = Math.addExact(value.evaluationCount, 1), updatedAt = now,
    )

    fun touched(value: GrowthEvidence, fact: EvidenceFact, activityWeight: Double, now: LocalDateTime) = value.copy(
        metricType = fact.metricType ?: value.metricType,
        averageConfidence = (value.averageConfidence * value.evidenceCount + fact.confidence) / (value.evidenceCount + 1),
        evidenceCount = Math.addExact(value.evidenceCount, 1),
        weightedEvidence = value.weightedEvidence + maxOf(0.0, activityWeight),
        recommendedFocus = fact.recommendedFocus?.takeIf { it.isNotBlank() } ?: value.recommendedFocus,
        lastSeenAt = now, updatedAt = now,
    )
}
