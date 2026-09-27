package jp.co.translacat.languagelearning.features.listening.domain.policy

import jp.co.translacat.languagelearning.features.listening.domain.model.*

/** 점수와 profile 자격은 기존 Python listening-scoring-half-up 및 BE 정책을 따른다. */
internal object ListeningScoring {
    val weights = mapOf(
        ListeningTaskType.DICTATION to linkedMapOf(
            "TOKEN_RECOGNITION" to .60, "OMISSION_ADDITION_ORDER" to .25, "ORTHOGRAPHY" to .15,
        ),
        ListeningTaskType.INTERPRETATION to linkedMapOf(
            "MEANING_FIDELITY" to .60, "DETAIL_AND_NUANCE" to .25, "ORIGIN_NATURALNESS" to .15,
        ),
        ListeningTaskType.COMPREHENSION to linkedMapOf("ANSWER_ACCURACY" to 1.0),
        ListeningTaskType.SUMMARY to linkedMapOf(
            "GIST_COVERAGE" to .55, "KEY_POINT_COVERAGE" to .30, "LANGUAGE_CLARITY" to .15,
        ),
        ListeningTaskType.REPEAT_AFTER_AUDIO to linkedMapOf(
            "PRONUNCIATION" to .40, "PROSODY_RHYTHM" to .25, "FLUENCY" to .20, "COMPLETENESS" to .15,
        ),
    )

    fun roundScore(value: Double) = (value + .5).toInt().coerceIn(0, 100)

    fun weighted(task: ListeningTaskType, metrics: List<ListeningMetric>): Int? {
        val expected = weights.getValue(task)
        require(metrics.size == expected.size && metrics.map { it.type }.toSet() == expected.keys)
        require(metrics.all { kotlin.math.abs(it.weight - expected.getValue(it.type)) <= 1e-9 })
        if (metrics.any { it.score == null }) return null
        return roundScore(metrics.sumOf { checkNotNull(it.score) * expected.getValue(it.type) })
    }

    fun assistance(context: ListeningEvaluationContext): String = when {
        context.revealed -> "GUIDED"
        context.assistance.any { it.type in setOf("TOPIC_HINT", "KEYWORD_HINT") } -> "ASSISTED"
        else -> "INDEPENDENT"
    }

    fun revealed(task: ListeningTaskType, context: ListeningEvaluationContext) = ListeningTaskResult(
        task, "NOT_EVALUABLE", false, reasonCode = "ANSWER_REVEALED", assistanceLevel = "GUIDED",
        improvements = listOf("정답 공개 후의 시도는 공식 평가와 Profile 반영에서 제외됩니다."),
        assistanceUsage = context.assistance,
    )

    fun finalize(result: ListeningTaskResult, context: ListeningEvaluationContext): ListeningTaskResult {
        // 기존 confidence 기준은 평가 가능성 판정이다. 이행 중 새 기준을 추가하지 않는다.
        val base = result.copy(assistanceLevel = assistance(context), assistanceUsage = context.assistance)
        val confidence = base.confidence ?: 0.0
        if (confidence < .70) {
            return base.copy(
                status = "NOT_EVALUABLE", evaluable = false, score = null,
                reasonCode = "LOW_CONFIDENCE", profileSignals = emptyList(), profileEligible = false,
            )
        }
        if (!context.official || context.revealed || !base.evaluable || base.score == null) return base

        // 공식 첫 시도만 기존 metric별 evidence 가중치로 profile 신호를 만든다.
        val scores = base.metrics.associate { it.type to checkNotNull(it.score) }
        val values = when (base.taskType) {
            ListeningTaskType.DICTATION -> listOf(
                Triple("LISTENING_RECOGNITION", base.score.toDouble(), 1.0),
                Triple("VOCABULARY", scores.getValue("TOKEN_RECOGNITION"), .60),
                Triple("ORTHOGRAPHY", scores.getValue("ORTHOGRAPHY"), .80),
            )

            ListeningTaskType.INTERPRETATION -> listOf(
                Triple("MEANING", scores.getValue("MEANING_FIDELITY"), 1.0),
                Triple("ORIGIN_NATURALNESS", scores.getValue("ORIGIN_NATURALNESS"), .30),
            )

            ListeningTaskType.COMPREHENSION, ListeningTaskType.SUMMARY -> listOf(
                Triple("MEANING", base.score.toDouble(), 1.0),
            )

            ListeningTaskType.REPEAT_AFTER_AUDIO -> listOf(
                Triple("PRONUNCIATION", scores.getValue("PRONUNCIATION"), 1.0),
                Triple("FLUENCY", scores.getValue("FLUENCY"), .80),
                Triple("LISTENING_RECOGNITION", base.score.toDouble(), .50),
            )
        }
        val evidenceIds = (1..minOf(base.evidence.size, 50)).map { "evidence-$it" }
        return base.copy(
            profileEligible = true,
            profileSignals = values.map { (metric, score, weight) ->
                ListeningProfileSignal(metric, score, confidence, weight, evidenceIds, base.taskType)
            },
        )
    }

    fun independence(normal: Long, slow: Long) =
        maxOf(60, 100 - minOf(maxOf(normal - 1, 0), 4).toInt() * 5 - minOf(maxOf(slow, 0), 2).toInt() * 10)

    fun adjustedOverall(content: Double, independence: Double) = roundScore(content * .85 + independence * .15)

    fun progressEligible(
        official: Boolean, practice: Boolean, revealed: Boolean, alreadyApplied: Boolean, statuses: List<String>,
    ) =
        official && !practice && !revealed && !alreadyApplied && statuses.isNotEmpty() && statuses.all {
            it in setOf(
                "EVALUATED", "NOT_EVALUABLE",
            )
        }
}
