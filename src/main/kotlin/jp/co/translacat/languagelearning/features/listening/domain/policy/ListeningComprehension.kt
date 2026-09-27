package jp.co.translacat.languagelearning.features.listening.domain.policy

import jp.co.translacat.languagelearning.features.listening.domain.model.*

internal object ListeningComprehension {
    fun evaluate(
        options: Map<String, String>, selected: String, correct: String, originLanguage: String,
        context: ListeningEvaluationContext,
    ): ListeningTaskResult {
        require(options.size in 2..4 && selected in options && correct in options)
        if (context.revealed) return ListeningScoring.revealed(ListeningTaskType.COMPREHENSION, context)

        // 정답 key의 객관 비교와 기존 언어별 안내문을 보존한다.
        val matches = selected == correct
        val copy = when {
            originLanguage.lowercase().startsWith("ja") -> listOf(
                if (matches) "正解を選べました。" else "正解は${correct}です。音声の重要な手掛かりをもう一度確認しましょう。",
                "音声の中心内容を正確に判断できました。",
                "選択肢を見る前に、音声の中心となる行動や意図を一文で整理してみましょう。",
            )

            originLanguage.lowercase().startsWith("ko") -> listOf(
                if (matches) "정답을 정확하게 골랐습니다." else "정답은 ${correct}입니다. 오디오의 핵심 단서를 다시 확인해 보세요.",
                "오디오의 핵심 내용을 정확히 판단했습니다.", "선택지보다 먼저 오디오의 핵심 행동·의도를 한 문장으로 정리해 보세요.",
            )

            else -> listOf(
                if (matches) "You selected the correct answer." else "The correct answer is $correct. Review the key clue in the audio.",
                "You identified the main content of the audio accurately.",
                "Before checking the options, summarize the main action or intent in one sentence.",
            )
        }
        val evidence = ListeningEvidence(
            "ANSWER_ACCURACY", copy[0], if (matches) "INFO" else "HIGH", reference = correct, recognized = selected,
        )
        val score = if (matches) 100 else 0
        return ListeningScoring.finalize(
            ListeningTaskResult(
                ListeningTaskType.COMPREHENSION, "EVALUATED", true, score, 1.0,
                metrics = listOf(ListeningMetric("ANSWER_ACCURACY", score.toDouble(), 1.0, 1.0, listOf(evidence))),
                evidence = listOf(evidence),
                strengths = if (matches) listOf(copy[1]) else emptyList(),
                improvements = if (matches) emptyList() else listOf(copy[2]),
                recommendedInterpretations = listOf(options.getValue(correct)),
            ),
            context,
        )
    }
}
