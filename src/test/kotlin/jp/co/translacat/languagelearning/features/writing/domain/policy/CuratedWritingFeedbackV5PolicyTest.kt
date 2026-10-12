package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import kotlin.test.*

class CuratedWritingFeedbackV5PolicyTest {
    private val requirements = listOf("C1" to "자신의 상황을 전달한다")
    private val free = context(WritingType.FREE)
    private val answer = "毎晩二十分本を読んでいます。"

    @Test
    fun `선택 개선도 네 의미 관계를 요구하며 관찰의 관계와 독립적으로 보존한다`() {
        // 준비: 선택 쉼표는 필수 오류가 아니지만 도움이 되는 선택적 명료화는 유지한다.
        val suggestion = optional("毎晩二十分、本を読んでいます。")
        val schema = CuratedWritingFeedbackV5Policy.schema(free, requirements, CuratedWritingFeedbackV5Policy.segments(answer))

        // 실행
        val saved = validate(result(optional = listOf(suggestion)))
        val fields = schema.getValue("properties").jsonObject.getValue("optionalAlternatives").jsonObject
            .getValue("items").jsonObject

        // 검증: 과거 v4 schema는 바꾸지 않고 새 필드를 누락한 v5 요청은 거절한다.
        assertTrue("relationChecks" in fields.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        val row = saved.getValue("optionalAlternatives").jsonArray.single().jsonObject
        assertEquals(answer, row.string("original"))
        assertEquals(suggestion.getValue("relationChecks"), row.getValue("relationChecks"))
        assertEquals("curated-writing-feedback-v5", saved.string("policyVersion"))
        assertTrue(saved.getValue("necessaryCorrections").jsonArray.isEmpty())
        assertFailsWith<IllegalArgumentException> { validate(result(optional = listOf(JsonObject(suggestion - "relationChecks")))) }
        assertFalse(schema.toString().contains("\"pattern\""))
    }

    @Test
    fun `표현만 바꾼 대안에 충돌이나 불확실한 관계가 있으면 전체 결과를 거절한다`() {
        // 준비: 관찰은 MET여도 대안이 의미를 바꾸면 별도의 거절 사유다.
        val keys = listOf("causality", "chronology", "specificity", "stance")

        // 실행 / 검증: 단어 목록이 아니라 명시된 관계 계약을 모든 축에 동일하게 적용한다.
        keys.forEach { key ->
            listOf("CONFLICT", "UNCERTAIN", "INVALID").forEach { state ->
                val suggestion = optional("毎晩二十分、本を読んでいます。", relation = key to state)
                assertFailsWith<IllegalArgumentException> { validate(result(optional = listOf(suggestion))) }
            }
        }
    }

    @Test
    fun `첫 v4 실제 실패 원문을 보존하고 새 관계를 자칭 정상으로 붙여도 허위 수량은 거절한다`() {
        // 준비: 실제 응답과 입력은 fixture 원문 그대로 읽는다. 새 필드 보완은 원인 분리용 메모리 입력뿐이다.
        val fixture = Json.parseToJsonElement(requireNotNull(javaClass.getResource(
            "/contracts/curated-writing-feedback-v4-optional-facts-failure.json")).readText(Charsets.UTF_8)).jsonObject
        val input = fixture.getValue("input").jsonObject
        val task = input.getValue("task").jsonObject
        val required = task.getValue("requirements").jsonArray.map { it.jsonObject.string("id") to it.jsonObject.string("text") }
        val ctx = CuratedWritingFeedbackV5Policy.Context(WritingType.valueOf(task.string("writingType")),
            task.getValue("publicPromptAndConditions").jsonObject, task.string("originLanguage"), task.string("learningLanguage"))
        val segments = CuratedWritingFeedbackV5Policy.segments(input.getValue("learnerAnswer").jsonObject.string("text"))
        val output = fixture.getValue("output").jsonObject
        val rawText = output.toString()

        // 실행 / 검증: 기존 형식은 새 성공으로 위장하지 않고, 모든 대안을 보존한 수량 검사도 실패한다.
        assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV5Policy.validate(output, ctx, required, segments) }
        val withChecks = JsonObject(output + ("optionalAlternatives" to JsonArray(output.getValue("optionalAlternatives").jsonArray.map { raw ->
            JsonObject(raw.jsonObject + mapOf(
                "relationChecks" to relations(),
                "originText" to JsonPrimitive("원래 뜻을 유지하는 표현이라고 가정한 검증 대조입니다."),
                "learningText" to JsonPrimitive("元の意味を保つ表現としての検証用の比較です。"),
            ))
        })))
        assertEquals(output.getValue("optionalAlternatives").jsonArray.map { it.jsonObject.string("replacement") },
            withChecks.getValue("optionalAlternatives").jsonArray.map { it.jsonObject.string("replacement") })
        assertEquals(2, withChecks.getValue("optionalAlternatives").jsonArray.size)
        assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV5Policy.validate(withChecks, ctx, required, segments) }
        assertEquals(rawText, output.toString())
    }

    @Test
    fun `학습자가 실제 명시한 수량은 보존하고 조건부 가상 예시는 구별한다`() {
        // 준비: 실제 수량을 갖는 정상 표현 대조와 실제 사실이 아닌 조건부 예시를 구별한다.
        val actual = "面談を十五分遅らせていただけないでしょうか。"
        val numericVariant = optional("面談を15分遅らせていただけないでしょうか。")
        val hypothetical = JsonObject(optional("例えば、毎晩三十分本を読むと仮定します。", relation = "specificity" to "CONFLICT") + mapOf(
            "suggestionKind" to JsonPrimitive("HYPOTHETICAL_EXAMPLE"),
            "originText" to JsonPrimitive("매일 삼십 분 읽는 경우를 가정한 예시이며 실제 상황을 뜻하지 않습니다."),
            "learningText" to JsonPrimitive("毎晩三十分読む場合を仮定した例で、実際の状況を示すものではありません。"),
        ))

        // 실행
        val exact = validate(result(optional = listOf(numericVariant)), actual)
        val imagined = validate(result(optional = listOf(hypothetical)))

        // 검증: 수량 표기 동등성을 거절하거나 모든 대안을 비우지 않는다.
        assertEquals(1, exact.getValue("optionalAlternatives").jsonArray.size)
        val row = imagined.getValue("optionalAlternatives").jsonArray.single().jsonObject
        assertEquals("HYPOTHETICAL_EXAMPLE", row.string("suggestionKind"))
        assertEquals("CONFLICT", row.getValue("relationChecks").jsonObject.string("specificity"))
        assertTrue(imagined.getValue("necessaryCorrections").jsonArray.isEmpty())
    }

    @Test
    fun `인용한 일본어는 허용하고 설명 밖 가나와 교정문 복사는 거절한다`() {
        // 준비: 같은 선택 개선에서 한국어 설명과 일본어 예문을 분리한다.
        val baseline = optional("毎晩二十分、本を読んでいます。")
        val normal = JsonObject(baseline + ("originText" to JsonPrimitive("「二十分」 뒤를 구분해 읽을 수 있는 선택적 표현입니다.")))
        val stray = JsonObject(baseline + ("originText" to JsonPrimitive("より 구분해서 읽을 수 있는 선택적 표현입니다.")))
        val duplicated = JsonObject(baseline + ("learningText" to baseline.getValue("replacement")))

        // 실행 / 검증: 원문에 있는 일본어를 금지하지 않고 설명 언어의 역할만 검사한다.
        assertEquals(1, validate(result(optional = listOf(normal))).getValue("optionalAlternatives").jsonArray.size)
        assertFailsWith<IllegalArgumentException> { validate(result(optional = listOf(stray))) }
        assertFailsWith<IllegalArgumentException> { validate(result(optional = listOf(duplicated))) }
    }

    @Test
    fun `새 선택 계약에서도 실제 언어 오류와 명시 사실 충돌의 유용한 교정을 유지한다`() {
        // 준비: 선택 대안 검사가 필요한 필수 교정을 막지 않아야 한다.
        val typo = "欠席した生徒も増っていました。"
        val form = correction("欠席した生徒も増えていました。")
        val fact = JsonObject(correction("期限を延長できます。") + mapOf(
            "issueType" to JsonPrimitive("TASK_FACT_CONFLICT"), "sourceFactId" to JsonPrimitive("T1"),
            "formReason" to JsonPrimitive("NONE"),
            "originText" to JsonPrimitive("과제에 명시된 연장 가능 여부와 일치하도록 부정을 고칩니다."),
            "learningText" to JsonPrimitive("課題に明示された延長の可否と一致するように否定を直します。"),
        ))

        // 실행 / 검증: 내용 MET와 실제 활용 오류는 양립하며 명시 사실은 세 유형 모두 근거가 될 수 있다.
        val saved = validate(result(corrections = listOf(form)), typo)
        assertEquals("MET", saved.getValue("observations").jsonArray.single().jsonObject.string("status"))
        assertEquals("欠席した生徒も増えていました。", saved.getValue("necessaryCorrections").jsonArray.single().jsonObject.string("replacement"))
        assertFailsWith<IllegalArgumentException> { validate(result(corrections = listOf(form)), "欠席した生徒も増えていました。") }
        WritingType.entries.forEach { type ->
            val ctx = context(type, listOf("기한을 연장할 수 있습니다."))
            val fixed = CuratedWritingFeedbackV5Policy.validate(result(observation = observation("UNMET", "stance" to "CONFLICT"),
                corrections = listOf(fact)), ctx, requirements, CuratedWritingFeedbackV5Policy.segments("期限を延長できません。"))
            assertEquals("T1", fixed.getValue("necessaryCorrections").jsonArray.single().jsonObject.string("sourceFactId"))
        }
    }

    @Test
    fun `개인 의견 강제와 선택 쉼표 필수화는 v5에서도 거절한다`() {
        // 준비
        val comma = correction("毎晩二十分、本を読んでいます。")
        val imposed = JsonObject(correction("インターネットを最も信頼しています。") + mapOf(
            "issueType" to JsonPrimitive("TASK_FACT_CONFLICT"), "sourceFactId" to JsonPrimitive("T1"), "formReason" to JsonPrimitive("NONE"),
        ))

        // 실행 / 검증: FREE의 선택 요구는 T1 사실 근거를 만들지 않는다.
        assertFailsWith<IllegalArgumentException> { validate(result(corrections = listOf(comma))) }
        assertTrue(CuratedWritingFeedbackV5Policy.taskFacts(free).isEmpty())
        assertFailsWith<IllegalArgumentException> {
            validate(result(observation("UNMET", "stance" to "CONFLICT"), corrections = listOf(imposed)), "インターネットが便利です。")
        }
    }

    private fun context(type: WritingType, facts: List<String> = emptyList()) = CuratedWritingFeedbackV5Policy.Context(type,
        buildJsonObject { put("originText", "기한을 연장할 수 있습니다."); put("providedFacts", JsonArray(facts.map(::JsonPrimitive))) }, "ko", "ja")

    private fun relations(change: Pair<String, String>? = null) = buildJsonObject {
        listOf("causality", "chronology", "specificity", "stance").forEach { put(it, if (it == change?.first) change.second else "PRESERVED") }
    }

    private fun observation(status: String = "MET", relation: Pair<String, String>? = null) = buildJsonObject {
        put("requirementId", "C1"); put("status", status); put("evidenceKind", "LEARNER_TEXT")
        put("segmentIds", buildJsonArray { add("S1") }); put("relationChecks", relations(relation))
    }

    private fun optional(replacement: String, relation: Pair<String, String>? = null) = buildJsonObject {
        put("suggestionKind", "EXPRESSION_ONLY"); put("segmentId", "S1"); put("replacement", replacement)
        put("relationChecks", relations(relation)); put("originText", "같은 의미를 유지하면서 읽는 구간을 구분하는 선택적 표현입니다.")
        put("learningText", "同じ意味を保ちながら、読む区切りを示す任意の表現です。")
    }

    private fun correction(replacement: String) = buildJsonObject {
        put("requirementId", "C1"); put("segmentId", "S1"); put("replacement", replacement)
        put("issueType", "LANGUAGE_FORM"); put("sourceFactId", ""); put("formReason", "INFLECTION")
        put("originText", "잘못된 활용을 고쳐 원래의 증가 의미를 전달합니다."); put("learningText", "誤った活用を直し、元の増加の意味を伝えます。")
    }

    private fun result(observation: JsonObject = observation(), corrections: List<JsonObject> = emptyList(), optional: List<JsonObject> = emptyList()) = buildJsonObject {
        put("observations", JsonArray(listOf(observation))); put("necessaryCorrections", JsonArray(corrections))
        put("optionalAlternatives", JsonArray(optional)); put("uncertainties", buildJsonArray {})
    }

    private fun validate(output: JsonObject, original: String = answer) = CuratedWritingFeedbackV5Policy.validate(
        output, free, requirements, CuratedWritingFeedbackV5Policy.segments(original),
    )

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
