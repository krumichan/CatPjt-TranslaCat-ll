package jp.co.translacat.languagelearning.features.keyword.domain.policy

import jp.co.translacat.languagelearning.features.growth.domain.model.KeywordMastery
import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordCandidate
import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordType
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.*

/** 기존 Core의 날짜별 난수와 숙련도·최근 선택 가중치를 그대로 사용한다. */
internal object KeywordSelectionPolicy {
    data class Selection(val candidate: KeywordCandidate, val canonicalKey: String, val weight: Double)
    private data class Weighted(val candidate: KeywordCandidate, val canonicalKey: String, val rawWeight: Double)

    fun select(
        userId: Long, date: LocalDate, candidates: List<KeywordCandidate>, limit: Int,
        mastery: (String) -> KeywordMastery?,
    ): List<Selection> {
        if (limit <= 0 || candidates.isEmpty()) return emptyList()

        // 후보 순서와 Java 난수 seed를 유지해 같은 입력의 선택 결과를 보존한다.
        val random = Random(Objects.hash(userId, date).toLong())
        val weighted = candidates.map { candidate ->
            val canonical = candidate.canonicalKey?.takeUnless(String::isBlank)
                ?: candidate.text.lowercase(Locale.ROOT)
            Weighted(candidate, canonical, rawWeight(candidate.availableFrom, mastery(canonical), date))
        }
        val selected = mutableListOf<Weighted>()
        val count = minOf(limit, weighted.size)
        fun pick(values: List<Weighted>): Weighted {
            var cursor = random.nextDouble() * values.sumOf { it.rawWeight }
            for (value in values) {
                cursor -= value.rawWeight
                if (cursor <= 0) return value
            }
            return values.last()
        }

        // 두 유형이 있으면 각 유형을 먼저 하나 선택하고 남은 자리를 가중 추출한다.
        val topics = weighted.filter { it.candidate.type == KeywordType.TOPIC }
        val vocabulary = weighted.filter { it.candidate.type == KeywordType.VOCABULARY }
        if (count >= 2 && topics.isNotEmpty() && vocabulary.isNotEmpty()) {
            selected += pick(topics)
            selected += pick(vocabulary)
        }
        while (selected.size < count) {
            val remaining = weighted.filterNot { it in selected }
            if (remaining.isEmpty()) break
            selected += pick(remaining)
        }
        val maximum = selected.maxOf { it.rawWeight }
        return selected.map {
            Selection(
                it.candidate, it.canonicalKey,
                Math.round((it.rawWeight / maxOf(maximum, 0.0001)).coerceIn(0.01, 1.0) * 10000.0) / 10000.0,
            )
        }
    }

    fun rawWeight(availableFrom: LocalDate, mastery: KeywordMastery?, date: LocalDate): Double {
        val activeDay = ChronoUnit.DAYS.between(availableFrom, date) + 1
        val ramp = when {
            activeDay <= 1 -> 0.25
            activeDay <= 3 -> 0.50
            activeDay <= 6 -> 0.75
            else -> 1.0
        }
        val proficiency = mastery?.let { 1.25 - it.score / 250.0 } ?: 1.10
        val elapsed = mastery?.lastSelectedDate?.let { ChronoUnit.DAYS.between(it, date) }
        val recency = when {
            elapsed == null -> 1.20
            elapsed <= 1 -> 0.55
            elapsed <= 3 -> 0.75
            elapsed <= 6 -> 1.00
            else -> 1.20
        }
        return maxOf(0.01, ramp * proficiency * recency)
    }
}
