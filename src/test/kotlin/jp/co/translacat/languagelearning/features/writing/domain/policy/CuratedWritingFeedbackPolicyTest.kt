package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import kotlin.test.*

class CuratedWritingFeedbackV3PolicyTest {
    private val answer = "欠席した生徒も増えていました。"
    private val requirements = listOf("C1" to "결석한 학생이 증가했음을 전달한다")
    private val segments = CuratedWritingFeedbackV3Policy.segments(answer)

    private fun modelResult(id: String = "S1", extra: Boolean = false) = buildJsonObject {
        put("observations", buildJsonArray { add(buildJsonObject {
            put("requirementId", "C1"); put("status", "MET")
            put("evidenceKind", "LEARNER_TEXT")
            put("segmentIds", buildJsonArray { add(id) })
            put("originText", "결석한 학생이 늘어난 사실을 전달했습니다.")
            put("learningText", "欠席した生徒が増えたことを伝えています。")
            if (extra) put("answerQuotes", buildJsonArray { add("増っていました") })
        }) })
        put("necessaryCorrections", buildJsonArray {})
        put("optionalAlternatives", buildJsonArray {})
        put("uncertainties", buildJsonArray {})
    }

    @Test
    fun `없는 오타 인용은 거절하고 서버가 실제 답안 원문만 복원한다`() {
        // 준비·실행: 모델은 구간 ID만 선택한다.
        val validated = CuratedWritingFeedbackV3Policy.validate(modelResult(), requirements, segments)

        // 검증: 과거 없는 오타 반례를 모델 필드에 넣으면 거절하고 정확한 원문만 남긴다.
        val quote = validated.getValue("observations").jsonArray.single().jsonObject
            .getValue("answerQuotes").jsonArray.single().jsonPrimitive.content
        assertEquals(answer, quote)
        assertFalse(quote.contains("増っていました"))
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV3Policy.validate(modelResult(extra = true), requirements, segments)
        }
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV3Policy.validate(modelResult(id = "S999"), requirements, segments)
        }
    }

    @Test
    fun `요구 ID의 중복 누락과 언어 lane 침범을 거절한다`() {
        // 준비: 필수 의미 두 개에 대해 한 개만 판정한 응답.
        val missing = modelResult()
        val two = requirements + ("C2" to "다른 사실")

        // 실행·검증: 숫자 ID 일치만으로 의미 충족을 주장하지 않고 전부 판정하게 한다.
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV3Policy.validate(missing, two, segments)
        }
        val wrongLanguage = JsonObject(missing + ("observations" to buildJsonArray { add(buildJsonObject {
            put("requirementId", "C1"); put("status", "MET")
            put("evidenceKind", "LEARNER_TEXT")
            put("segmentIds", buildJsonArray { add("S1") })
            put("originText", "日本語だけ")
            put("learningText", "한국어가 섞였습니다")
        }) }))
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV3Policy.validate(wrongLanguage, requirements, segments)
        }
    }

    @Test
    fun `실제 B4 일본어 설명 실패 원본을 거절하고 언어만 고친 대비 응답은 누락 근거를 보존한다`() {
        // 준비: 첫 v3 실모델 response의 output_text를 바이트 그대로 보존한 fixture다.
        val raw = requireNotNull(javaClass.getResource("/contracts/curated-writing-feedback-v3-origin-language-failure.json"))
            .readText(Charsets.UTF_8)
        val output = Json.parseToJsonElement(raw).jsonObject
        val actualRequirements = listOf(
            "C1" to "상대의 사정이나 기존 계획을 먼저 인정했는가?",
            "C2" to "자신의 사정을 설명하며 요청을 지나치게 단정하지 않고 완곡하게 표현했는가?",
            "C3" to "가능한 대안이나 조건의 범위를 제시했는가?",
            "C4" to "수신자에 맞는 정중한 어조를 사용했는가?",
        )
        val original = "すでに会場を予約されていることは承知しておりますが、もし可能でしたら面談の時間を少し遅らせていただけないでしょうか。難しければ、別の日でも構いません。"
        val actualSegments = CuratedWritingFeedbackV3Policy.segments(original)
        val observations = output.getValue("observations").jsonArray
        val failed = observations.last().jsonObject.getValue("originText").jsonPrimitive.content
        assertEquals("敬語の丁寧な表現（〜いただけないでしょうか、〜でも構いません）を使用しています.", failed)

        // 실행 / 검증: Provider가 응답했더라도 언어 계약을 어긴 원본은 저장 검증에서 거절해야 한다.
        val error = assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV3Policy.validate(output, actualRequirements, actualSegments)
        }
        assertEquals("WRITING_FEEDBACK_SCHEMA_INVALID", error.message)

        // 실행: 대조군은 메모리에서 C4 설명 언어만 바꾼다. 실제 원본과 기대 조건은 수정하지 않는다.
        val contrast = JsonObject(output + ("observations" to JsonArray(observations.map { row ->
            val value = row.jsonObject
            if (value.getValue("requirementId").jsonPrimitive.content != "C4") value
            else JsonObject(value + ("originText" to JsonPrimitive("정중한 표현 ‘いただけないでしょうか’를 사용했습니다.")))
        })))
        val saved = CuratedWritingFeedbackV3Policy.validate(contrast, actualRequirements, actualSegments)

        // 검증: 언어 제약은 개인 사정을 발명하거나 누락 판정을 없애지 않는다.
        val missing = saved.getValue("observations").jsonArray[1].jsonObject
        assertEquals("UNMET", missing.getValue("status").jsonPrimitive.content)
        assertEquals("MISSING_REQUIREMENT", missing.getValue("evidenceKind").jsonPrimitive.content)
        assertTrue(missing.getValue("answerQuotes").jsonArray.isEmpty())
        assertTrue(saved.getValue("necessaryCorrections").jsonArray.isEmpty())
        assertTrue(saved.getValue("optionalAlternatives").jsonArray.isEmpty())
    }

    @Test
    fun `긴 문단과 빈 줄을 버리지 않고 최대 여든 구간으로 묶는다`() {
        // 준비·실행: 답안의 문장 수는 하루 목표 문항 수와 별개다.
        val paragraph = (1..100).joinToString("\n\n") { "文${it}です。" }
        val parts = CuratedWritingFeedbackV3Policy.segments(paragraph)

        // 검증: 공백 줄까지 포함한 원문이 정확히 재구성된다.
        assertTrue(parts.size <= 80)
        assertEquals(paragraph, parts.joinToString("") { it.text })
    }

    @Test
    fun `FREE 참고 답안의 사정만 바꾸어도 평가 입력은 동일하고 실제 학습자 사정은 남는다`() {
        // 준비: 참고 예시의 출장과 질병은 학습자의 사실이 아니다.
        val public = buildJsonObject { put("originText", "자신의 사정을 설명하고 일정 변경을 요청하세요.") }
        fun reference(example: String) = buildJsonObject {
            put("referenceAnswers", buildJsonArray { add(example) })
            put("alternativeNote", example)
        }
        val learner = "出張があるため、面談の時間を変更していただけないでしょうか。"
        val parts = CuratedWritingFeedbackV3Policy.segments(learner)

        // 실행: FREE는 참고 예시를 제외하고, 번역에서는 출처를 분리한 예시로만 전달한다.
        val first = CuratedWritingFeedbackV3Policy.prompt(WritingType.FREE, public, reference("出張"), learner, requirements, parts)
        val second = CuratedWritingFeedbackV3Policy.prompt(WritingType.FREE, public, reference("病気"), learner, requirements, parts)
        val translation = CuratedWritingFeedbackV3Policy.prompt(WritingType.TRANSLATION, public,
            reference("出張"), learner, requirements, parts)

        // 검증: 출장이라는 단어를 금지하지 않으며 실제 작성한 원문을 그대로 전달한다.
        assertEquals(first, second)
        val input = Json.parseToJsonElement(first).jsonObject
        assertFalse("referenceExamples" in input)
        assertEquals(learner, input.getValue("learnerAnswer").jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("FREE", input.getValue("task").jsonObject.getValue("writingType").jsonPrimitive.content)
        assertTrue("referenceExamples" in Json.parseToJsonElement(translation).jsonObject)
    }

    @Test
    fun `누락은 요구 근거만 저장하며 허위 인용과 개인 사실 보충 교정을 거절한다`() {
        // 준비: 실제 원문은 요청만 하고 개인 사정을 쓰지 않았다.
        val missing = buildJsonObject {
            put("requirementId", "C1"); put("status", "UNMET"); put("evidenceKind", "MISSING_REQUIREMENT")
            put("segmentIds", buildJsonArray {})
            put("originText", "요청 이유가 드러나지 않습니다. 자신의 사정을 덧붙여 주세요.")
            put("learningText", "依頼の理由が示されていません。ご自身の事情を補ってください。")
        }
        val result = JsonObject(modelResult() + ("observations" to JsonArray(listOf(missing))))

        // 실행: 유용한 누락 설명은 성공 결과로 보존한다.
        val validated = CuratedWritingFeedbackV3Policy.validate(result, requirements, segments)

        // 검증: 존재하지 않는 이유의 인용이나 교정은 저장되지 않는다.
        assertEquals(0, validated.getValue("observations").jsonArray.single().jsonObject.getValue("answerQuotes").jsonArray.size)
        val invented = correction("実は出張があるため、時間を変更していただけないでしょうか。")
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV3Policy.validate(JsonObject(result + ("necessaryCorrections" to JsonArray(listOf(invented)))), requirements, segments)
        }
        val falseQuote = JsonObject(missing + ("segmentIds" to JsonArray(listOf(JsonPrimitive("S1")))))
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV3Policy.validate(JsonObject(result + ("observations" to JsonArray(listOf(falseQuote)))), requirements, segments)
        }
    }

    @Test
    fun `실제 오타와 부정 반전의 교정은 남기고 원문 ID를 정확히 복원한다`() {
        // 준비: 정상 표현을 오타로 간주하는 반례와 실제 오타를 구분한다.
        val typo = "欠席した生徒も増っていました。"
        val contradiction = "期限を延長することはできません。"
        val corrected = listOf(typo to answer, contradiction to "期限を延長することができます。")

        // 실행·검증: 정당한 기존 문장 교정까지 비워서 오류를 숨기지 않는다.
        corrected.forEach { (original, replacement) ->
            val observed = modelResult().getValue("observations").jsonArray.single().jsonObject
            val unmet = JsonObject(observed + ("status" to JsonPrimitive("UNMET")))
            val result = JsonObject(modelResult() + mapOf(
                "observations" to JsonArray(listOf(unmet)),
                "necessaryCorrections" to JsonArray(listOf(correction(replacement))),
            ))
            val saved = CuratedWritingFeedbackV3Policy.validate(result, requirements, CuratedWritingFeedbackV3Policy.segments(original))
            val row = saved.getValue("necessaryCorrections").jsonArray.single().jsonObject
            assertEquals(original, row.getValue("original").jsonPrimitive.content)
            assertEquals(replacement, row.getValue("replacement").jsonPrimitive.content)
        }
    }

    @Test
    fun `가상 제안은 선택 예시로만 분류하며 미분류 대안을 거절한다`() {
        // 준비: 가상 내용은 학습자의 실제 사실이나 필요한 교정이 아니다.
        val optional = JsonObject(correction("例えば、明日は図書館で辞書を調べます。") - "requirementId" +
            ("suggestionKind" to JsonPrimitive("HYPOTHETICAL_EXAMPLE")))
        val result = JsonObject(modelResult() + ("optionalAlternatives" to JsonArray(listOf(optional))))

        // 실행
        val validated = CuratedWritingFeedbackV3Policy.validate(result, requirements, segments)

        // 검증: 가상 분류를 그대로 저장하고, 옛 모양의 분류 없는 새 모델 응답은 거절한다.
        assertEquals("HYPOTHETICAL_EXAMPLE", validated.getValue("optionalAlternatives").jsonArray.single()
            .jsonObject.getValue("suggestionKind").jsonPrimitive.content)
        assertTrue(validated.getValue("necessaryCorrections").jsonArray.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            val unclassifiedAlternatives = JsonArray(listOf(JsonObject(optional - "suggestionKind")))
            val unclassified = JsonObject(result + ("optionalAlternatives" to unclassifiedAlternatives))
            CuratedWritingFeedbackV3Policy.validate(unclassified, requirements, segments)
        }
    }

    @Test
    fun `의미 조건을 충족해도 실제 형태 오류의 필수 교정을 보존한다`() {
        // 준비: MET은 요구 내용의 충족이며 실제 형태가 정상이라는 보증이 아니다.
        val original = "欠席した生徒も増っていました。"
        val result = JsonObject(modelResult() + ("necessaryCorrections" to JsonArray(listOf(correction(answer)))))

        // 실행
        val saved = CuratedWritingFeedbackV3Policy.validate(result, requirements, CuratedWritingFeedbackV3Policy.segments(original))

        // 검증: 요구 상태를 임의로 UNMET으로 바꾸지 않아도 실제 오타를 유용하게 고칠 수 있다.
        assertEquals("MET", saved.getValue("observations").jsonArray.single().jsonObject.getValue("status").jsonPrimitive.content)
        val corrected = saved.getValue("necessaryCorrections").jsonArray.single().jsonObject
        assertEquals(original, corrected.getValue("original").jsonPrimitive.content)
        assertEquals(answer, corrected.getValue("replacement").jsonPrimitive.content)
    }

    @Test
    fun `표현만 바꾼다는 대안이 새 수량 범위 날짜를 만들면 거절한다`() {
        // 준비: 첫 실모델 결과는 少し를 없는 10〜15분으로 구체화하면서 EXPRESSION_ONLY로 표시했다.
        val pairs = listOf(
            "面談の時間を少し遅らせていただけないでしょうか。" to "面談の開始時刻を10〜15分ほど遅らせていただけないでしょうか。",
            "三十分遅れます。" to "二十〜三十分遅れます。",
            "10月3日に伺います。" to "１０月４日に伺います。",
            "本を二冊借ります。" to "本を三冊借ります。",
        )

        // 실행 / 검증: 실제 답안에 없는 수치만 차단하고 대안 전체를 없애지는 않는다.
        pairs.forEach { (original, replacement) ->
            assertFailsWith<IllegalArgumentException> {
                CuratedWritingFeedbackV3Policy.validate(optionalResult(replacement), requirements,
                    CuratedWritingFeedbackV3Policy.segments(original))
            }
        }
    }

    @Test
    fun `한자 전각 아라비아 숫자와 기간 단위의 같은 수량은 허용한다`() {
        // 준비: 수량 표기만 다른 정상 표현을 새 학습자 사실로 오인하지 않는다.
        val pairs = listOf(
            "旅行ガイドを一週間借ります。" to "旅行ガイドを1週間借ります。",
            "旅行ガイドを１週間借ります。" to "旅行ガイドを一週間借ります。",
            "三十分待ちます。" to "30分待ちます。",
            "一時間待ちます。" to "60分待ちます。",
            "二〇二六年十月三日に伺います。" to "2026年10月3日に伺います。",
            "一万二千円かかります。" to "12000円かかります。",
            "2人で参加します。" to "二人で参加します。",
        )

        // 실행 / 검증: 서버는 원문과 대안을 그대로 보존하며 우열 판단을 덧붙이지 않는다.
        pairs.forEach { (original, replacement) ->
            val saved = CuratedWritingFeedbackV3Policy.validate(optionalResult(replacement), requirements,
                CuratedWritingFeedbackV3Policy.segments(original))
            val optional = saved.getValue("optionalAlternatives").jsonArray.single().jsonObject
            assertEquals(original, optional.getValue("original").jsonPrimitive.content)
            assertEquals(replacement, optional.getValue("replacement").jsonPrimitive.content)
            assertEquals("EXPRESSION_ONLY", optional.getValue("suggestionKind").jsonPrimitive.content)
        }
    }

    @Test
    fun `충분함을 뜻하는 十分에 없는 시간 수치를 요구하지 않는다`() {
        // 준비: 같은 한자 표기를 사용하는 형용 표현과 시간 수치를 구분한다.
        val pairs = listOf(
            "よく確認します。" to "十分に確認します。",
            "必要な準備をします。" to "十分な準備をします。",
            "10分に一回確認します。" to "十分に一回確認します。",
        )

        // 실행 / 검증: 정상 표현은 그대로 보존한다.
        pairs.forEach { (original, replacement) ->
            val saved = CuratedWritingFeedbackV3Policy.validate(optionalResult(replacement), requirements,
                CuratedWritingFeedbackV3Policy.segments(original))
            assertEquals(replacement, saved.getValue("optionalAlternatives").jsonArray.single()
                .jsonObject.getValue("replacement").jsonPrimitive.content)
        }
    }

    @Test
    fun `실제 十分 지연과 간격은 충분함 예외로 우회하지 않는다`() {
        // 준비: 十分만으로 끝나는 서술은 시간일 수도 있으므로 형용 표현이라고 단정하지 않는다.
        val pairs = listOf(
            "少し遅れます。" to "十分遅れます。",
            "時々確認します。" to "十分に一回確認します。",
            "少しです。" to "十分です。",
            "少しだ。" to "十分だ。",
            "十分に確認します。" to "10分確認します。",
        )

        // 실행 / 검증: 실제 답안에 없던 시간 수치는 계속 거절한다.
        pairs.forEach { (original, replacement) ->
            assertFailsWith<IllegalArgumentException> {
                CuratedWritingFeedbackV3Policy.validate(optionalResult(replacement), requirements,
                    CuratedWritingFeedbackV3Policy.segments(original))
            }
        }
    }

    @Test
    fun `새 수량을 조건부 예시로 명시하면 실제 교정과 구분하여 보존한다`() {
        // 준비: 새 사실을 정당한 기존 답안 교정으로 둔갑시키지 않는 명시적 가상 예시다.
        val original = "面談の時間を少し遅らせていただけないでしょうか。"
        val replacement = "例えば、十〜十五分ほど遅らせていただけないでしょうか。"

        // 실행
        val saved = CuratedWritingFeedbackV3Policy.validate(optionalResult(replacement, "HYPOTHETICAL_EXAMPLE"),
            requirements, CuratedWritingFeedbackV3Policy.segments(original))

        // 검증
        assertTrue(saved.getValue("necessaryCorrections").jsonArray.isEmpty())
        assertEquals("HYPOTHETICAL_EXAMPLE", saved.getValue("optionalAlternatives").jsonArray.single()
            .jsonObject.getValue("suggestionKind").jsonPrimitive.content)
    }

    private fun optionalResult(replacement: String, kind: String = "EXPRESSION_ONLY"): JsonObject {
        val optional = JsonObject(correction(replacement) - "requirementId" + ("suggestionKind" to JsonPrimitive(kind)))
        return JsonObject(modelResult() + ("optionalAlternatives" to JsonArray(listOf(optional))))
    }

    private fun correction(replacement: String) = buildJsonObject {
        put("requirementId", "C1"); put("segmentId", "S1"); put("replacement", replacement)
        put("originText", "원문에서 확인한 부분의 표현을 고칩니다.")
        put("learningText", "原文で確認できる箇所の表現を修正します。")
    }
}
