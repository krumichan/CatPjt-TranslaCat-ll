package jp.co.translacat.languagelearning.features.writing.domain.policy

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor

internal data class WritingRawScores(
    val meaning: Double,
    val grammar: Double,
    val vocabulary: Double,
    val naturalness: Double,
    val expression: Double,
) {
    init {
        require(listOf(meaning, grammar, vocabulary, naturalness, expression).all { it.isFinite() && it in 0.0..100.0 })
    }
}

internal data class WritingScores(
    val overall: Int,
    val meaning: Int,
    val grammar: Int,
    val vocabulary: Int,
    val naturalness: Int,
    val expression: Int,
)

/** Python writing.policy의 Decimal(str(value)), ROUND_HALF_UP와 의미 cap을 보존한다. */
internal object WritingScoring {
    const val rubricVersion = "writing-evaluation-rubric"
    const val policyVersion = "writing-scoring-policy"

    fun score(raw: WritingRawScores): WritingScores {
        val weighted = listOf(
            raw.meaning to "0.30", raw.grammar to "0.25", raw.vocabulary to "0.15",
            raw.naturalness to "0.20", raw.expression to "0.10",
        ).fold(BigDecimal.ZERO) { sum, (value, weight) ->
            sum + BigDecimal.valueOf(value) * BigDecimal(weight)
        }
        val unbounded = weighted.setScale(0, RoundingMode.HALF_UP).toInt()
        val cap = when {
            raw.meaning <= 29 -> 49
            raw.meaning <= 49 -> 69
            else -> 100
        }

        fun rounded(value: Double): Int = floor(value + 0.5).toInt().coerceIn(0, 100)
        return WritingScores(
            unbounded.coerceAtMost(cap), rounded(raw.meaning), rounded(raw.grammar),
            rounded(raw.vocabulary), rounded(raw.naturalness), rounded(raw.expression),
        )
    }
}
