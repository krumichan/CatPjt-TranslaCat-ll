package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import kotlin.test.*

class CuratedWritingFeedbackV6PolicyTest {
    private val required = listOf("C1" to "자신의 상황을 전달한다")
    private val answer = "毎晩二十分本を読んでいます。"
    private val free = context()

    @Test
    fun `요구의 빈 분해나 일부 coverage만으로 충족을 만들지 못한다`() {
        // 준비
        val normal = observation(listOf(part(required.single().second)))
        val partial = observation(listOf(part("자신의 상황")))
        val empty = observation(emptyList())

        // 실행 / 검증
        assertEquals("MET", saved(result(normal)).observation().string("status"))
        listOf(partial, empty, observation(listOf(part("자신의 상황을 전달한다 ")))).forEach {
            assertFailsWith<IllegalArgumentException> { saved(result(it)) }
        }
    }

    @Test
    fun `없는 개인 사정과 실제 요청 근거를 구별하고 부분 누락을 보충 요청으로 조립한다`() {
        // 준비: 정확한 원문 요구를 두 부분으로 나누고 실제 표현이 없는 사정에는 인용을 붙이지 않는다.
        val requirement = listOf("C1" to "사정을 밝히고 요청한다")
        val text = "面談を遅らせていただけないでしょうか。"
        val parts = listOf(part("사정을 밝히고 ", "ABSENT"), part("요청한다", quote = text))

        // 실행
        val row = saved(result(observation(parts)), text, requirement).observation()

        // 검증: 기존 guard를 완화하지 않고 혼합 요구의 실제 표현과 없는 부분을 모두 드러낸다.
        assertEquals("UNMET", row.string("status"))
        assertEquals("LEARNER_TEXT", row.string("evidenceKind"))
        assertTrue(row.string("originText").contains("보충할 부분: 사정을 밝히고"))
        assertEquals(listOf(text), row.getValue("answerQuotes").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(row.getValue("requirementParts").jsonArray.first().jsonObject.getValue("evidence").jsonArray.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            saved(result(observation(listOf(part("사정을 밝히고 ", "ABSENT", quote = text), part("요청한다", quote = text)))), text, requirement)
        }
    }

    @Test
    fun `완전히 없는 요구는 인용 없는 누락으로 남기고 사실을 대신 쓰지 않는다`() {
        // 준비
        val observation = observation(listOf(part(required.single().second, "ABSENT")))

        // 실행 / 검증
        val row = saved(result(observation)).observation()
        assertEquals("MISSING_REQUIREMENT", row.string("evidenceKind"))
        assertTrue(row.getValue("answerQuotes").jsonArray.isEmpty())
        assertTrue(row.string("originText").contains("자신의 실제 내용"))
        assertFailsWith<IllegalArgumentException> { saved(result(observation, corrections = listOf(correction("二十分", "三十分")))) }
    }

    @Test
    fun `누락과 불확실 또는 충돌을 집계하고 관계 enum만으로 상태를 바꾸지 못한다`() {
        // 준비
        val requirement = listOf("C1" to "사정과 요청")
        val uncertain = observation(listOf(part("사정과 ", "ABSENT"), part("요청", "UNCERTAIN")), "stance" to "UNCERTAIN")
        val conflict = observation(listOf(part("사정과 ", "ABSENT"), part("요청", "CONFLICT")), "stance" to "CONFLICT")

        // 실행 / 검증
        assertEquals("UNMET", saved(result(uncertain), requirements = requirement).observation().string("status"))
        assertEquals("UNMET", saved(result(conflict), requirements = requirement).observation().string("status"))
        assertFailsWith<IllegalArgumentException> { saved(result(observation(listOf(part(required.single().second)), "stance" to "CONFLICT"))) }
        assertFailsWith<IllegalArgumentException> { saved(result(observation(listOf(part(required.single().second, "UNCERTAIN"))))) }
    }

    @Test
    fun `실제 quote와 모든 명시 사실의 coverage를 확인한다`() {
        // 준비
        val guided = context(WritingType.GUIDED, listOf("마감 연장이 가능합니다.", "접수는 입구입니다."))
        val normal = observation(listOf(part(required.single().second, facts = listOf("T1", "T2"))))

        // 실행 / 검증: 사실 ID는 문자열 근거를 대신하지 않으며 실제 의미 정확성은 별도 수용 대상이다.
        assertEquals("MET", saved(result(normal), ctx = guided).observation().string("status"))
        assertFailsWith<IllegalArgumentException> { saved(result(observation(listOf(part(required.single().second, facts = listOf("T1"))))), ctx = guided) }
        assertFailsWith<IllegalArgumentException> { saved(result(observation(listOf(part(required.single().second, quote = "存在しない原文"))))) }
    }

    @Test
    fun `실제 언어 오류는 부분 누락과 공존해도 정확한 표현 근거에서 고친다`() {
        // 준비
        val text = "欠席した生徒も増っていました。"
        val requirement = listOf("C1" to "상황과 이유")
        val observation = observation(listOf(part("상황과 ", quote = "増っていました"), part("이유", "ABSENT")))

        // 실행
        val result = saved(result(observation, corrections = listOf(correction("増って", "増えて"))), text, requirement)

        // 검증
        assertEquals("UNMET", result.observation().string("status"))
        val row = result.getValue("necessaryCorrections").jsonArray.single().jsonObject
        assertEquals("欠席した生徒も増えていました。", row.string("replacement"))
        assertTrue(row.string("originText").contains("활용 오류"))
        assertEquals(text, row.string("original"))
    }

    @Test
    fun `명시 사실 교정은 해당 충돌 근거 내부만 바꾸고 다른 정상 절은 보존한다`() {
        // 준비
        val text = "受付は入口で、期限は延長できません。"
        val ctx = context(WritingType.GUIDED, listOf("기한을 연장할 수 있습니다."))
        val observation = observation(listOf(part(required.single().second, "CONFLICT", "期限は延長できません", listOf("T1"))), "stance" to "CONFLICT")
        val repair = factCorrection("できません", "できます")

        // 실행 / 검증
        val row = saved(result(observation, corrections = listOf(repair)), text, ctx = ctx).getValue("necessaryCorrections").jsonArray.single().jsonObject
        assertEquals("受付は入口で、期限は延長できます。", row.string("replacement"))
        assertTrue(row.string("originText").contains("기한을 연장할 수 있습니다"))
        assertFailsWith<IllegalArgumentException> { saved(result(observation, corrections = listOf(factCorrection("入口", "出口"))), text, ctx = ctx) }
    }

    @Test
    fun `정상 선택 개선은 충족한 요구와 실제 근거에 연결해 국소 변경한다`() {
        // 준비
        val suggestion = optional("二十分", "二十分、")

        // 실행
        val row = saved(result(optional = listOf(suggestion))).getValue("optionalAlternatives").jsonArray.single().jsonObject

        // 검증: 선택 쉼표는 필수 교정 없이 유용한 명료화로 남는다.
        assertEquals("毎晩二十分、本を読んでいます。", row.string("replacement"))
        assertEquals("C1", row.string("requirementId"))
        assertEquals(answer, row.string("original"))
        assertTrue(row.string("originText").contains("선택적"))
        assertEquals(suggestion.getValue("patch"), row.getValue("patch"))
    }

    @Test
    fun `미충족 개인 선택을 표현 대안으로 대신 채우거나 필수 교정 뒤의 뜻으로 대안을 만들지 못한다`() {
        // 준비
        val text = "インターネットが便利です。"
        val unmet = observation(listOf(part(required.single().second, "CONFLICT", text)), "stance" to "CONFLICT")
        val typo = "欠席した生徒も増っていました。"

        // 실행 / 검증
        assertFailsWith<IllegalArgumentException> {
            saved(result(unmet, optional = listOf(optional("便利です", "最も信頼でき、医療サイトを比較した経験があります"))), text)
        }
        assertFailsWith<IllegalArgumentException> {
            saved(result(observation(listOf(part(required.single().second, quote = typo))),
                listOf(correction("増って", "増えて")), listOf(optional("増って", "増加して"))), typo)
        }
    }

    @Test
    fun `가상 예시는 누락 요구에서도 사실과 분리해 유지한다`() {
        // 준비
        val missing = observation(listOf(part(required.single().second, "ABSENT")))
        val hypothetical = JsonObject(optional("二十分", "例えば三十分") + mapOf(
            "suggestionKind" to JsonPrimitive("HYPOTHETICAL_EXAMPLE"), "relationChecks" to relations("specificity" to "CONFLICT"),
        ))

        // 실행 / 검증
        val row = saved(result(missing, optional = listOf(hypothetical))).getValue("optionalAlternatives").jsonArray.single().jsonObject
        assertEquals("HYPOTHETICAL_EXAMPLE", row.string("suggestionKind"))
        assertTrue(row.string("originText").contains("실제 사실이나 필수 교정이 아닌 가상 예시"))
    }

    @Test
    fun `빈 동일 중복 위치 patch는 거절하며 단일 삭제는 허용한다`() {
        // 준비
        val duplicate = "本を読み、本を読みます。"

        // 실행 / 검증
        listOf(optional("", "、"), optional("二十分", "二十分"), optional("未知", "既知")).forEach {
            assertFailsWith<IllegalArgumentException> { saved(result(optional = listOf(it))) }
        }
        assertFailsWith<IllegalArgumentException> {
            saved(result(observation(listOf(part(required.single().second, quote = duplicate))), optional = listOf(optional("本を", "雑誌を"))), duplicate)
        }
        val deletion = saved(result(optional = listOf(optional("毎晩", "")))).getValue("optionalAlternatives").jsonArray.single().jsonObject
        assertEquals("二十分本を読んでいます。", deletion.string("replacement"))
    }

    @Test
    fun `독립된 실제 오류 두 개는 비중첩 patch로 각각 고칠 수 있다`() {
        // 준비
        val text = "生徒も増っていて、本を読んでいす。"
        val observation = observation(listOf(part(required.single().second, quote = text)))
        val repairs = listOf(correction("増って", "増えて"), correction("読んでいす", "読んでいます"))

        // 실행 / 검증
        assertEquals(2, saved(result(observation, repairs), text).getValue("necessaryCorrections").jsonArray.size)
        assertFailsWith<IllegalArgumentException> { saved(result(observation, repairs + repairs.first()), text) }
    }

    @Test
    fun `수량 표기 단위 구두점만의 차이는 필수 교정이 아니고 실제 숫자 충돌은 고친다`() {
        // 준비
        val variants = listOf("20分", "２０分", "二十分間", "1200秒")

        // 실행 / 검증: 기존 수량 파서의 동등성을 재사용하며 단어별 QA 예외를 추가하지 않는다.
        variants.forEach { variant ->
            assertEquals(CuratedWritingFeedbackV3Policy.quantityNotationComparable(answer),
                CuratedWritingFeedbackV3Policy.quantityNotationComparable(answer.replace("二十分", variant + "、")))
            assertFailsWith<IllegalArgumentException> { saved(result(corrections = listOf(correction("二十分", variant + "、")))) }
        }
        val ctx = context(WritingType.GUIDED, listOf("독서 시간은 삼십 분입니다."))
        val conflict = observation(listOf(part(required.single().second, "CONFLICT", facts = listOf("T1"))), "specificity" to "CONFLICT")
        assertEquals("毎晩三十分本を読んでいます。", saved(result(conflict, listOf(factCorrection("二十分", "三十分"))), ctx = ctx)
            .getValue("necessaryCorrections").jsonArray.single().jsonObject.string("replacement"))
    }

    @Test
    fun `일본어 설명은 유지하고 provider의 한국어 설명 필드는 schema에서 제거한다`() {
        // 준비
        val schema = CuratedWritingFeedbackV6Policy.schema(free, required, CuratedWritingFeedbackV6Policy.segments(answer))
        val normal = optional("二十分", "二十分、")

        // 실행 / 검증
        assertFalse(schema.toString().contains("originText"))
        assertFalse(schema.toString().contains("\"pattern\""))
        assertEquals(1, saved(result(optional = listOf(normal))).getValue("optionalAlternatives").jsonArray.size)
        assertFailsWith<IllegalArgumentException> { saved(result(optional = listOf(JsonObject(normal + ("originText" to JsonPrimitive("任意です。")))))) }
        assertFailsWith<IllegalArgumentException> { saved(result(optional = listOf(JsonObject(normal + ("learningText" to JsonPrimitive("일본어 설명이 아닙니다.")))))) }
        assertFailsWith<IllegalArgumentException> { saved(result(optional = listOf(JsonObject(normal + ("learningText" to JsonPrimitive("毎晩二十分、本を読んでいます。")))))) }
    }

    @Test
    fun `표현 대안의 허위 수량과 존재하지 않는 근거는 기존 검증에서도 계속 거절한다`() {
        // 준비 / 실행 / 검증
        assertFailsWith<IllegalArgumentException> { saved(result(optional = listOf(optional("二十分", "三十分")))) }
        assertFailsWith<IllegalArgumentException> { saved(result(optional = listOf(JsonObject(optional("二十分", "二十分、") + ("requirementId" to JsonPrimitive("C2")))))) }
        val uncertain = JsonObject(optional("二十分", "二十分、") + ("relationChecks" to relations("stance" to "UNCERTAIN")))
        assertFailsWith<IllegalArgumentException> { saved(result(optional = listOf(uncertain))) }
    }

    @Test
    fun `v5 실제 실패 원문 다섯 건은 당시 통과와 거절을 정확 재현하며 새 성공으로 바꾸지 않는다`() {
        // 준비: output_text만 담은 보존 fixture다. Provider 추론이나 암호화 필드는 포함하지 않는다.
        val fixtures = Json.parseToJsonElement(requireNotNull(javaClass.getResource(
            "/contracts/curated-writing-feedback-v5-development-failures.json")).readText(Charsets.UTF_8)).jsonArray
        val rejected = setOf("reg-gen-g-f1a", "reg-f03-actual-futtemashita-typo")

        // 실행 / 검증: 구형 성공 세 건의 의미 실패도 schema 성공과 별도로 보존한다.
        assertEquals(5, fixtures.size)
        fixtures.forEach { raw ->
            val fixture = raw.jsonObject
            val input = fixture.getValue("input").jsonObject
            val task = input.getValue("task").jsonObject
            val req = task.getValue("requirements").jsonArray.map { it.jsonObject.string("id") to it.jsonObject.string("text") }
            val ctx = CuratedWritingFeedbackV5Policy.Context(WritingType.valueOf(task.string("writingType")),
                task.getValue("publicPromptAndConditions").jsonObject, task.string("originLanguage"), task.string("learningLanguage"))
            val segments = CuratedWritingFeedbackV5Policy.segments(input.getValue("learnerAnswer").jsonObject.string("text"))
            val output = fixture.getValue("output")
            assertEquals(Json.parseToJsonElement(fixture.string("outputText")), output)
            if (fixture.string("caseId") in rejected) assertFailsWith<IllegalArgumentException> {
                CuratedWritingFeedbackV5Policy.validate(output, ctx, req, segments)
            } else assertEquals("curated-writing-feedback-v5", CuratedWritingFeedbackV5Policy.validate(output, ctx, req, segments).string("policyVersion"))
            assertFailsWith<IllegalArgumentException> {
                CuratedWritingFeedbackV6Policy.validate(output, context(ctx.writingType, public = ctx.public), req, segments)
            }
        }
    }

    private fun context(type: WritingType = WritingType.FREE, facts: List<String> = emptyList(), public: JsonObject? = null) =
        CuratedWritingFeedbackV6Policy.Context(type, public ?: buildJsonObject {
            put("originText", "기한을 연장할 수 있습니다."); put("providedFacts", JsonArray(facts.map(::JsonPrimitive)))
        }, "ko", "ja")
    private fun relations(change: Pair<String, String>? = null) = buildJsonObject {
        listOf("causality", "chronology", "specificity", "stance").forEach { put(it, if (it == change?.first) change.second else "PRESERVED") }
    }
    private fun part(text: String, state: String = "PRESENT", quote: String? = if (state == "ABSENT") null else answer, facts: List<String> = emptyList()) = buildJsonObject {
        put("requirementQuote", text); put("status", state)
        put("evidence", buildJsonArray { if (quote != null) add(buildJsonObject { put("segmentId", "S1"); put("quote", quote) }) })
        put("taskFactIds", JsonArray(facts.map(::JsonPrimitive)))
    }
    private fun observation(parts: List<JsonObject> = listOf(part(required.single().second)), relation: Pair<String, String>? = null) = buildJsonObject {
        put("requirementId", "C1"); put("requirementParts", JsonArray(parts)); put("relationChecks", relations(relation))
    }
    private fun patch(from: String, to: String) = buildJsonObject { put("original", from); put("replacement", to) }
    private fun optional(from: String, to: String) = buildJsonObject {
        put("requirementId", "C1"); put("segmentId", "S1"); put("suggestionKind", "EXPRESSION_ONLY")
        put("patch", patch(from, to)); put("relationChecks", relations())
        put("learningText", "意味を保ちながら、読む区切りを明確にする任意の表現です。")
    }
    private fun correction(from: String, to: String) = buildJsonObject {
        put("requirementId", "C1"); put("segmentId", "S1"); put("patch", patch(from, to))
        put("issueType", "LANGUAGE_FORM"); put("formReason", "INFLECTION"); put("sourceFactId", "")
        put("learningText", "動詞の活用を直し、元の意味を保った文にします。")
    }
    private fun factCorrection(from: String, to: String) = JsonObject(correction(from, to) + mapOf(
        "issueType" to JsonPrimitive("TASK_FACT_CONFLICT"), "formReason" to JsonPrimitive("NONE"), "sourceFactId" to JsonPrimitive("T1"),
    ))
    private fun result(observation: JsonObject = observation(), corrections: List<JsonObject> = emptyList(), optional: List<JsonObject> = emptyList()) = buildJsonObject {
        put("observations", JsonArray(listOf(observation))); put("necessaryCorrections", JsonArray(corrections))
        put("optionalAlternatives", JsonArray(optional)); put("uncertainties", buildJsonArray {})
    }
    private fun saved(output: JsonObject, text: String = answer, requirements: List<Pair<String, String>> = required,
                      ctx: CuratedWritingFeedbackV6Policy.Context = free) = CuratedWritingFeedbackV6Policy.validate(output, ctx, requirements, CuratedWritingFeedbackV6Policy.segments(text))
    private fun JsonObject.observation() = getValue("observations").jsonArray.single().jsonObject
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
