package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 현재 Python DifficultyRecoveryState의 슬롯별 단방향 추가 생성 정책. */
internal class WritingDifficultyRecovery(private val targetBand: Int) {
    init {
        require(targetBand in 1..5)
    }

    private val observed = mutableMapOf<Int, Int>()
    private var lastObservedAttempt = 0
    var direction: String? = null
        private set

    fun record(estimatedBand: Int?, difficultyStatus: String, reason: String, attempt: Int): Boolean {
        if (reason != "VERIFIED_BAND_MISMATCH" || difficultyStatus != "ASSESSED" ||
            estimatedBand == null || estimatedBand !in 1..5 || estimatedBand == targetBand
        ) return false
        observed[estimatedBand] = (observed[estimatedBand] ?: 0) + 1
        lastObservedAttempt = attempt
        return true
    }

    fun selectAfterRound(attempt: Int, attemptLimit: Int): Boolean {
        if (direction != null || attempt >= attemptLimit || lastObservedAttempt != attempt) return false
        val lower = observed.filterKeys { it < targetBand }.values.sum()
        val higher = observed.filterKeys { it > targetBand }.values.sum()
        direction = when {
            lower >= 2 && higher == 0 -> "INCREASE_PRODUCTION_DEMAND"
            higher >= 2 && lower == 0 -> "DECREASE_PRODUCTION_DEMAND"
            else -> null
        }
        return direction != null
    }

    fun payload(): JsonObject? {
        val selected = direction ?: return null

        // 방향은 실제 최종 불일치 두 건에서만 선택되며 남은 한 차례의 생성 지시로 전달한다.
        return buildJsonObject {
            put("policyVersion", "writing-difficulty-control-v1")
            put("direction", selected)
            put("targetBand", targetBand)
            put(
                "observedBandCounts",
                buildJsonObject {
                    for ((band, count) in observed.toSortedMap()) put(band.toString(), count)
                },
            )
            put("additionalGenerationRounds", 1)
            put(
                "instruction",
                "Use the selected productionBlueprint to change the ACTUAL meaning demands, " +
                    "not the band label, focusReason, sentence padding or specialist vocabulary. " +
                    "This is the final targeted generation round.",
            )
        }
    }
}
