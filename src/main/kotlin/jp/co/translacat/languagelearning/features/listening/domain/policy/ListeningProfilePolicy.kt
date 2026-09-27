package jp.co.translacat.languagelearning.features.listening.domain.policy

import java.time.LocalDateTime

/** 기존 BE ListeningProfilePolicy의 고정 가중치와 최근 30개 활동 계산이다. */
internal object ListeningProfilePolicy {
    data class Signal(
        val activityId: String, val score: Double, val confidence: Double, val assistance: String?,
        val evidenceWeight: Double, val official: Boolean, val practice: Boolean, val excluded: Boolean,
        val createdAt: LocalDateTime,
    )

    data class Aggregate(val score: Double?, val sampleCount: Int, val confidence: String)
    data class Growth(
        val active: Boolean, val previousAverage: Double?, val recentAverage: Double?, val delta: Double?,
        val sampleCount: Int,
    )

    data class Weakness(
        val state: String, val sampleCount: Int, val weakCount: Int, val recoveryCount: Int, val recentAverage: Double?,
    )

    val metrics = listOf(
        "LISTENING_RECOGNITION", "LISTENING_INDEPENDENCE", "VOCABULARY", "ORTHOGRAPHY", "MEANING",
        "ORIGIN_NATURALNESS", "PRONUNCIATION", "FLUENCY", "SPOKEN_EXPRESSION", "INTERACTION", "WRITTEN_EXPRESSION",
    )

    fun recencyWeight(rank: Int) = when (rank) {
        in 1..5 -> 1.0
        in 6..10 -> .85
        in 11..20 -> .70
        in 21..30 -> .55
        else -> 0.0
    }

    fun assistanceWeight(level: String?) = when (level) {
        null, "INDEPENDENT" -> 1.0; "ASSISTED" -> .85; else -> 0.0
    }

    fun finalWeight(rank: Int, confidence: Double, assistance: String?, evidence: Double) =
        if (confidence < .70 || evidence <= 0) 0.0 else recencyWeight(rank) * confidence.coerceIn(0.0, 1.0) *
            assistanceWeight(assistance) * evidence.coerceIn(0.0, 1.0)

    fun aggregate(source: List<Signal>): Aggregate {
        // 공식·비공개 정답·충분한 confidence 근거를 활동별 최신 한 건만 남긴다.
        val values = eligible(source)
        val weights = values.mapIndexed { index, signal ->
            finalWeight(
                index + 1, signal.confidence, signal.assistance, signal.evidenceWeight,
            )
        }
        val denominator = weights.sum()
        if (denominator == 0.0 || values.size < 3) return Aggregate(null, values.size, "DATA_COLLECTING")

        val score = values.zip(weights).sumOf { (signal, weight) -> signal.score * weight } / denominator
        return Aggregate(
            score, values.size,
            when {
                values.size >= 10 -> "HIGH"; values.size >= 5 -> "MEDIUM"; else -> "LOW"
            },
        )
    }

    fun growth(source: List<Signal>, currentlyActive: Boolean): Growth {
        val values = eligible(source)
        val recent = values.take(5)
        val previous = values.drop(recent.size).take(5)
        if (values.size < 6 || recent.size < 3 || previous.size < 3) return Growth(false, null, null, null, values.size)

        // 과거 다섯 활동과 최근 다섯 활동을 confidence·도움·evidence 가중 평균으로 비교한다.
        val recentAverage = windowAverage(recent)
        val previousAverage = windowAverage(previous)
        val delta = recentAverage - previousAverage
        return Growth(
            delta >= if (currentlyActive) 3.0 else 5.0, previousAverage, recentAverage, delta,
            recent.size + previous.size,
        )
    }

    fun weakness(source: List<Signal>): Weakness {
        val values = eligible(source).take(5)
        if (values.size < 3) return Weakness("DATA_COLLECTING", values.size, 0, 0, null)
        val weak = values.count { classify(it.score) == "WEAK_EVIDENCE" }
        val recovery = values.count { classify(it.score) == "RECOVERY_EVIDENCE" }
        val average = values.map { it.score }.average()
        val state = when {
            recovery >= 4 && weak == 0 && average >= 75 -> "RESOLVED"
            recovery >= 3 && average >= 70 -> "IMPROVING"
            weak >= 2 -> "ACTIVE"
            else -> "DATA_COLLECTING"
        }
        return Weakness(state, values.size, weak, recovery, average)
    }

    fun classify(score: Double) = when {
        score < 65 -> "WEAK_EVIDENCE"; score < 75 -> "NEUTRAL"; else -> "RECOVERY_EVIDENCE"
    }

    private fun eligible(source: List<Signal>) =
        source.filter { it.official && !it.practice && !it.excluded && it.confidence >= .70 && it.evidenceWeight > 0 }
            .sortedByDescending { it.createdAt }.distinctBy { it.activityId }.take(30)

    private fun windowAverage(values: List<Signal>): Double {
        val weights = values.map {
            it.confidence.coerceIn(0.0, 1.0) * assistanceWeight(
                it.assistance,
            ) * it.evidenceWeight.coerceIn(0.0, 1.0)
        }
        val denominator = weights.sum()
        return if (denominator == 0.0) 0.0 else values.zip(weights)
            .sumOf { (value, weight) -> value.score * weight } / denominator
    }
}
