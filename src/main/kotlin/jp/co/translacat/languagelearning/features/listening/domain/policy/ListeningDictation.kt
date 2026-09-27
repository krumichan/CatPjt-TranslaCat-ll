package jp.co.translacat.languagelearning.features.listening.domain.policy

import jp.co.translacat.languagelearning.features.listening.domain.model.*

internal object ListeningDictation {
    fun evaluate(
        source: String, answer: String, locale: String, variants: Map<String, List<String>> = emptyMap(),
        context: ListeningEvaluationContext = ListeningEvaluationContext(),
    ): ListeningTaskResult {
        if (context.revealed) return ListeningScoring.revealed(ListeningTaskType.DICTATION, context)

        // 정규화·허용 표기·token 정렬을 적용한 뒤 기존 세 항목의 점수를 계산한다.
        val normalized = ListeningText.normalize(source, locale)
        val canonical = ListeningText.canonicalize(ListeningText.normalize(answer, locale), variants, locale)
        val summary = ListeningAlignment.align(
            normalized.tokens, ListeningText.tokenize(canonical.text, locale), canonical.acceptedTokens,
        )
        val denominator = maxOf(summary.referenceCount, summary.answerCount, 1)
        val structureErrors = summary.count("OMISSION") + summary.count("ADDITION") + summary.count("ORDER")
        val scores = listOf(
            ListeningScoring.roundScore(summary.recognitionRatio * 100),
            ListeningScoring.roundScore(maxOf(0.0, 1.0 - structureErrors.toDouble() / denominator) * 100),
            ListeningScoring.roundScore(ListeningAlignment.sequenceRatio(normalized.text, canonical.text) * 100),
        )
        val feedback =
            listOf("들은 Token의 인식 정확도를 확인했습니다.", "누락·추가·어순 Alignment를 확인했습니다.", "Locale 정규화 후 표기 정확도를 확인했습니다.")
        val metrics =
            ListeningScoring.weights.getValue(ListeningTaskType.DICTATION).entries.mapIndexed { index, (type, weight) ->
                ListeningMetric(
                    type, scores[index].toDouble(), weight, 1.0, listOf(ListeningEvidence(type, feedback[index])),
                )
            }

        // 불일치 근거와 공식 profile 신호는 점수와 같은 정렬 결과에 연결한다.
        val evidence = summary.entries.filter { it.status !in setOf("MATCH", "ACCEPTED_VARIANT") }.map { entry ->
            val explanation = when (entry.status) {
                "OMISSION" -> "원문 「${entry.source}」가 답변에서 누락되었습니다."
                "ADDITION" -> "원문에 없는 「${entry.answer}」가 답변에 추가되었습니다."
                "SUBSTITUTION" -> "원문 「${entry.source}」를 「${entry.answer}」로 다르게 받아썼습니다."
                else -> "원문 「${entry.source}」와 답변 「${entry.answer}」의 순서가 다릅니다."
            }
            ListeningEvidence(
                "TOKEN_RECOGNITION", explanation,
                if (entry.status in setOf("OMISSION", "SUBSTITUTION")) "HIGH" else "MEDIUM",
                reference = entry.source, recognized = entry.answer,
            )
        }
        val score = checkNotNull(ListeningScoring.weighted(ListeningTaskType.DICTATION, metrics))
        return ListeningScoring.finalize(
            ListeningTaskResult(
                ListeningTaskType.DICTATION, "EVALUATED", true, score, 1.0,
                metrics = metrics, alignment = summary.entries, evidence = evidence,
                strengths = listOf(
                    when {
                        evidence.isEmpty() -> "원문의 Token과 순서를 정확히 받아썼습니다."
                        score >= 90 -> "대부분의 Token과 어순을 정확히 받아썼습니다."
                        else -> "정확히 인식한 Token을 기준으로 문장을 재구성했습니다."
                    },
                ),
                improvements = improvements(summary.entries, locale),
            ),
            context,
        )
    }

    private fun improvements(entries: List<ListeningAlignmentEntry>, locale: String): List<String> {
        val result = mutableListOf<String>()
        val advice = linkedMapOf(
            "OMISSION" to "짧게 발음되는 표현이 빠지지 않았는지 해당 구간을 다시 들어 보세요.",
            "ADDITION" to "익숙한 표현을 예상해서 들리지 않은 말을 덧붙이지 않았는지 확인해 보세요.",
            "ORDER" to "각 단어를 따로 맞히기보다 앞뒤 표현을 한 덩어리로 다시 들어 보세요.",
            "SUBSTITUTION" to "비슷하게 들린 소리를 원문과 비교하며 해당 구간을 다시 확인해 보세요.",
        )
        advice.forEach { (status, detail) ->
            val sample = entries.filter { it.status == status }.take(3)
            if (sample.isNotEmpty()) {
                val label = when (status) {
                    "OMISSION" -> "누락: " + sample.filter { it.source != null }.joinToString("、") { "「${it.source}」" }
                    "ADDITION" -> "추가: " + sample.filter { it.answer != null }.joinToString("、") { "「${it.answer}」" }
                    "ORDER" -> "어순: " + sample.joinToString("、") { "「${it.source ?: "None"}」→「${it.answer ?: "None"}」" }
                    else -> "다르게 받아씀: " + sample.joinToString("、") { "원문 「${it.source}」 / 내 답변 「${it.answer}」" }
                }
                val index = entries.indexOf(sample.first())
                val window = entries.subList(maxOf(0, index - 4), minOf(entries.size, index + 5))
                val separator = if (locale.lowercase().substringBefore('-') in setOf("ja", "zh")) "" else " "
                val source = window.mapNotNull { it.source }.joinToString(separator)
                val answer = window.mapNotNull { it.answer }.joinToString(separator)
                val comparison = listOfNotNull(
                    source.takeIf { it.isNotEmpty() }?.let { "원문 구간 「$it」" },
                    answer.takeIf { it.isNotEmpty() }?.let { "내 답변 구간 「$it」" },
                ).joinToString(" / ")
                result += "$label. " + if (comparison.isNotEmpty()) "$comparison. $detail" else detail
            }
        }
        return result
    }
}
