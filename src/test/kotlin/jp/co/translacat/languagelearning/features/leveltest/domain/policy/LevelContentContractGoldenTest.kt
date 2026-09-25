package jp.co.translacat.languagelearning.features.leveltest.domain.policy

import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelContentContract.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 기존 BE의 내용 검증 fixture를 Kotlin 테스트로 유지한다. 저장소 wrapper는 순수 값 입력으로 바꾼다. */
class LevelContentContractGoldenTest {
    private val policy = Inspector()

    private data class Question(
        val domain: LevelTestDomain,
        val type: LevelTestItemType,
        val mode: LevelTestAnswerMode,
        val answerLanguage: String?,
        val prompt: String,
        val options: List<Option>,
        val key: AnswerKey,
        val reference: Map<String, Any?>,
    )

    private class Inspector {
        private val value = LevelContentContract()

        fun inspect(question: Question, learningLanguage: String? = null): Health = value.inspectValues(
            domain = question.domain,
            itemType = question.type,
            instruction = "지시에 따라 답하세요.",
            instructionLanguage = "ko",
            learningLanguage = learningLanguage,
            answerMode = question.mode,
            answerLanguage = question.answerLanguage,
            promptText = question.prompt,
            options = question.options,
            answerKey = question.key,
            referencePayload = question.reference,
            maxAnswerLength = if (question.mode == LevelTestAnswerMode.TEXT) 500 else null,
            maxAudioSeconds = if (question.mode == LevelTestAnswerMode.AUDIO) 30 else null,
        )
    }

    @Test
    fun `writing translation requires exact origin source payload`() {
        val response = question(
            LevelTestDomain.WRITING,
            LevelTestItemType.WRITING_TRANSLATION,
            LevelTestAnswerMode.TEXT,
            "ja",
            "회의 시간이 변경되었으니 참석이 어려운 경우 알려주세요.",
            emptyList(),
            AnswerKey(null, emptyList(), null, emptyMap()),
            mapOf("translationSourceText" to "회의 시간이 변경되었으니 참석이 어려운 경우 알려주세요."),
        )
        assertTrue(policy.inspect(response).valid)

        val invalid = question(
            LevelTestDomain.WRITING,
            LevelTestItemType.WRITING_TRANSLATION,
            LevelTestAnswerMode.TEXT,
            "ja",
            "회의 시간이 변경되었습니다.",
            emptyList(),
            AnswerKey(null, emptyList(), null, emptyMap()),
            mapOf("translationSourceText" to "다른 문장"),
        )
        assertEquals("WRITING_TRANSLATION_SOURCE_INVALID", policy.inspect(invalid).reason)
    }

    @Test
    fun `guided writing rejects insufficient facts and intents`() {
        val response = question(
            LevelTestDomain.WRITING,
            LevelTestItemType.WRITING_SCENARIO_RESPONSE,
            LevelTestAnswerMode.TEXT,
            "ja",
            "現在の状況を説明し、提案メールを書いてください。",
            emptyList(),
            AnswerKey(null, emptyList(), null, emptyMap()),
            mapOf(
                "providedFacts" to listOf("入力が重複している"),
                "requiredIntents" to listOf("問題を説明する"),
                "responseConstraints" to listOf("丁寧体を使う"),
            ),
        )
        assertFalse(policy.inspect(response).valid)
        assertEquals("GUIDED_TASK_FACTS_INSUFFICIENT", policy.inspect(response).reason)
    }

    @Test
    fun `reading discourse requires visible structured emphasis`() {
        val emphasized = "これは、新たな戦略が必要であることを示している。"
        val passage = "売上が前年同期比で減少し、競合製品の台頭も確認された。" + emphasized +
            "今後は顧客層を再定義し、施策を見直す必要がある。"
        val question = "上記の強調部分は文章全体でどのような役割を果たしているか。"
        val response = question(
            LevelTestDomain.READING,
            LevelTestItemType.READING_DISCOURSE_FUNCTION,
            LevelTestAnswerMode.CHOICE,
            null,
            "$passage\n\n$question",
            listOf(
                Option("A", "原因分析"),
                Option("B", "前文を受けた評価"),
                Option("C", "例示"),
                Option("D", "反論"),
            ),
            AnswerKey("B", emptyList(), "BEST_ANSWER", mapOf("A" to 0, "B" to 100, "C" to 10, "D" to 0)),
            mapOf("emphasisText" to emphasized, "readingPassage" to passage, "readingQuestion" to question),
        )
        assertTrue(policy.inspect(response).valid)
    }

    @Test
    fun `reading rejects prompt without structured passage and question`() {
        val prompt = "今日は駅前の図書館へ行きました。静かで勉強しやすい場所でした。\n\n" +
            "筆者が図書館について述べていることは何ですか。"
        val response = question(
            LevelTestDomain.READING,
            LevelTestItemType.READING_DETAIL,
            LevelTestAnswerMode.CHOICE,
            null,
            prompt,
            listOf(
                Option("A", "静かだった"), Option("B", "混んでいた"),
                Option("C", "遠かった"), Option("D", "閉まっていた"),
            ),
            AnswerKey("A", emptyList(), "UNIQUE_ANSWER", mapOf("A" to 100, "B" to 0, "C" to 0, "D" to 0)),
            emptyMap(),
        )
        assertFalse(policy.inspect(response).valid)
        assertEquals("READING_STRUCTURE_INVALID", policy.inspect(response).reason)
    }

    @Test
    fun `listening requires separate learner question and keeps script hidden`() {
        val sourceText = "来週末のパーティーは田中さんの家で開き、料理は持ち寄りにする予定です。"
        val questionText = "この音声メッセージの主な内容は何ですか。"
        val response = question(
            LevelTestDomain.LISTENING,
            LevelTestItemType.LISTENING_GIST_CHOICE,
            LevelTestAnswerMode.CHOICE,
            null,
            questionText,
            listeningOptions(),
            AnswerKey("A", emptyList(), "UNIQUE_ANSWER", mapOf("A" to 100, "B" to 0, "C" to 0, "D" to 0)),
            mapOf("sourceText" to sourceText, "listeningQuestion" to questionText),
        )
        assertTrue(policy.inspect(response).valid)
    }

    @Test
    fun `listening rejects full audio script leak in prompt`() {
        val sourceText = "来週末のパーティーは田中さんの家で開き、料理は持ち寄りにする予定です。"
        val response = question(
            LevelTestDomain.LISTENING,
            LevelTestItemType.LISTENING_GIST_CHOICE,
            LevelTestAnswerMode.CHOICE,
            null,
            sourceText,
            listeningOptions(),
            AnswerKey("A", emptyList(), "UNIQUE_ANSWER", mapOf("A" to 100, "B" to 0, "C" to 0, "D" to 0)),
            mapOf("sourceText" to sourceText, "listeningQuestion" to sourceText),
        )
        assertFalse(policy.inspect(response).valid)
        assertEquals("LISTENING_SCRIPT_LEAK", policy.inspect(response).reason)
    }

    @Test
    fun `listening allows short keyword reference without exposing transcript`() {
        val sourceText = "新製品の海外展開では、現地の流通チャネルを早期に確保することが最も重要です。" +
            "法務確認や品質管理も必要ですが、販売網がなければ市場に届けられません。"
        val questionText = "この音声によると、海外展開で最も重要な課題は何ですか。"
        val response = question(
            LevelTestDomain.LISTENING,
            LevelTestItemType.LISTENING_DETAIL_CHOICE,
            LevelTestAnswerMode.CHOICE,
            null,
            questionText,
            listOf(
                Option("A", "最終品質チェックの完了"), Option("B", "流通チャネルの確保"),
                Option("C", "法務部門との連携"), Option("D", "来月の役員会議での報告"),
            ),
            AnswerKey("B", emptyList(), "UNIQUE_ANSWER", mapOf("A" to 0, "B" to 100, "C" to 0, "D" to 0)),
            mapOf("sourceText" to sourceText, "listeningQuestion" to questionText),
        )
        assertTrue(policy.inspect(response).valid)
    }

    @Test
    fun `vocab paraphrase uses structured emphasis without raw underline markup`() {
        val response = vocabQuestion("予定を見合わせることになりました。")
        assertTrue(policy.inspect(response, "ja").valid)
    }

    @Test
    fun `vocab paraphrase rejects raw underline markup even with emphasis metadata`() {
        val response = vocabQuestion("予定を<u>見合わせる</u>ことになりました。")
        assertFalse(policy.inspect(response, "ja").valid)
    }

    @Test
    fun `listening choice rejects origin language question and options in Japanese lane`() {
        val sourceText = "会議は午後三時から始まります。"
        val questionText = "이 음성의 중심 내용은 무엇입니까?"
        val response = question(
            LevelTestDomain.LISTENING,
            LevelTestItemType.LISTENING_GIST_CHOICE,
            LevelTestAnswerMode.CHOICE,
            null,
            questionText,
            listOf(Option("A", "회의 시간"), Option("B", "날씨"), Option("C", "여행"), Option("D", "주문")),
            AnswerKey("A", emptyList(), "UNIQUE_ANSWER", mapOf("A" to 100, "B" to 0, "C" to 0, "D" to 0)),
            mapOf("sourceText" to sourceText, "listeningQuestion" to questionText),
        )
        assertFalse(policy.inspect(response, "ja").valid)
        assertEquals("LEARNER_TEXT_LANGUAGE_MISMATCH", policy.inspect(response, "ja").reason)
    }

    private fun listeningOptions() = listOf(
        Option("A", "パーティーの準備について"),
        Option("B", "旅行の予定について"),
        Option("C", "仕事の報告について"),
        Option("D", "買い物の相談について"),
    )

    private fun vocabQuestion(prompt: String) = question(
        LevelTestDomain.VOCABULARY,
        LevelTestItemType.VOCAB_PARAPHRASE_CHOICE,
        LevelTestAnswerMode.CHOICE,
        null,
        prompt,
        listOf(Option("A", "延期する"), Option("B", "開始する"), Option("C", "忘れる"), Option("D", "確認する")),
        AnswerKey("A", emptyList(), "UNIQUE_ANSWER", mapOf("A" to 100, "B" to 0, "C" to 0, "D" to 0)),
        mapOf("emphasisText" to "見合わせる"),
    )

    private fun question(
        domain: LevelTestDomain,
        type: LevelTestItemType,
        mode: LevelTestAnswerMode,
        language: String?,
        prompt: String,
        options: List<Option>,
        key: AnswerKey,
        reference: Map<String, Any?>,
    ) = Question(domain, type, mode, language, prompt, options, key, reference)
}
