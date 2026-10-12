package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import kotlin.test.*

class CuratedWritingFeedbackV4PolicyTest {
    private val requirements = listOf("C1" to "상황에 맞는 자신의 내용을 전달한다")
    private val answer = "欠席した生徒も増えていました。"
    private val free = context(WritingType.FREE)
    private val originalCases by lazy {
        Json.parseToJsonElement(requireNotNull(javaClass.getResource("/contracts/curated-writing-feedback-v3-core-six.json"))
            .readText(Charsets.UTF_8)).jsonArray.map { it.jsonObject }
    }

    @Test
    fun `원본 여섯 실제 응답은 v3 구조 통과 다섯과 언어 거절 하나로 재현된다`() {
        // 준비: 실제 Provider 입력과 output_text를 보존한 fixture이며 새 모델 호출은 없다.
        var accepted = 0
        var rejected = 0
        originalCases.forEach { case ->
            val input = case.getValue("input").jsonObject
            val task = input.getValue("task").jsonObject
            val required = task.getValue("requirements").jsonArray.map {
                it.jsonObject.string("id") to it.jsonObject.string("text")
            }
            val segments = CuratedWritingFeedbackV4Policy.segments(input.getValue("learnerAnswer").jsonObject.string("text"))

            // 실행 / 검증: 구조 통과는 의미 성공이 아니며 원본 오류를 expected로 고치지 않는다.
            if (case.string("caseId") == "C2") {
                assertFailsWith<IllegalArgumentException> {
                    CuratedWritingFeedbackV3Policy.validate(case.getValue("output"), required, segments)
                }
                rejected++
            } else {
                val result = CuratedWritingFeedbackV3Policy.validate(case.getValue("output"), required, segments)
                assertEquals("curated-writing-feedback-v3", result.string("policyVersion"))
                accepted++
            }
            assertEquals("FAIL", case.string("semanticAcceptance"))
        }
        assertEquals(5, accepted)
        assertEquals(1, rejected)
    }

    @Test
    fun `첫 v4 실모델의 근거 없는 선택 수량은 원본 그대로 거절한다`() {
        // 준비: 실제 output_text의 선택 대안을 삭제하거나 고치지 않은 무과금 replay다.
        val fixture = Json.parseToJsonElement(requireNotNull(javaClass.getResource(
            "/contracts/curated-writing-feedback-v4-optional-facts-failure.json")).readText(Charsets.UTF_8)).jsonObject
        val input = fixture.getValue("input").jsonObject
        val task = input.getValue("task").jsonObject
        val required = task.getValue("requirements").jsonArray.map { it.jsonObject.string("id") to it.jsonObject.string("text") }
        val ctx = CuratedWritingFeedbackV4Policy.Context(WritingType.valueOf(task.string("writingType")),
            task.getValue("publicPromptAndConditions").jsonObject, task.string("originLanguage"), task.string("learningLanguage"))
        val parts = CuratedWritingFeedbackV4Policy.segments(input.getValue("learnerAnswer").jsonObject.string("text"))
        val output = fixture.getValue("output").jsonObject

        // 실행 / 검증: schema 수용과 내용 전달은 다르며 잘못된 선택 대안이 있으면 전체 결과를 거절한다.
        assertEquals(2, output.getValue("optionalAlternatives").jsonArray.size)
        assertTrue(output.getValue("necessaryCorrections").jsonArray.isEmpty())
        assertTrue(output.getValue("observations").jsonArray.all { it.jsonObject.string("status") == "MET" })
        val failed = assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV4Policy.validate(output, ctx, required, parts)
        }
        assertEquals("WRITING_FEEDBACK_SCHEMA_INVALID", failed.message)

        // 검증: 새 관계 필드를 걷어 기존 서버의 수량 검사를 직접 통과시켜도 같은 선택 대안은 거절된다.
        // 이는 원인 분리용 메모리 입력이며 실제 원본이나 저장 결과를 성공으로 바꾸지 않는다.
        val legacyShape = JsonObject(output + ("observations" to JsonArray(output.getValue("observations").jsonArray.map { raw ->
            JsonObject(raw.jsonObject - "relationChecks" + mapOf(
                "originText" to JsonPrimitive("주어진 요구와 답안의 관계를 확인했습니다."),
                "learningText" to JsonPrimitive("提示された要件と回答の関係を確認しました。"),
            ))
        })))
        assertEquals(output.getValue("optionalAlternatives"), legacyShape.getValue("optionalAlternatives"))
        assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV3Policy.validate(legacyShape, required, parts) }
    }

    @Test
    fun `원본 인과 설명과 사물 구체화는 모델 재서술 대신 원문과 요구를 정확히 보존한다`() {
        // 준비: C1과 C5의 원문은 그대로 사용한다. 모델 관계 판단을 숨기지 않고 별도로 저장한다.
        listOf("C1", "C5").forEach { id ->
            val case = originalCases.single { it.string("caseId") == id }
            val input = case.getValue("input").jsonObject
            val learner = input.getValue("learnerAnswer").jsonObject.string("text")
            val oldObservation = case.getValue("output").jsonObject.getValue("observations").jsonArray.first().jsonObject
            val wrongProse = if (id == "C1") oldObservation.string("originText") else
                case.getValue("output").jsonObject.getValue("observations").jsonArray[2].jsonObject.string("originText")
            val parts = CuratedWritingFeedbackV4Policy.segments(learner)
            val sourceSegment = if (id == "C1") parts.first() else parts[2]

            // 실행: 관찰의 자유 설명 자체는 새 모델 schema에 없고 서버가 저장된 요구·원문을 붙인다.
            val saved = CuratedWritingFeedbackV4Policy.validate(result(observation(segment = sourceSegment.id)), free, requirements, parts)
            val row = saved.getValue("observations").jsonArray.single().jsonObject

            // 검증: 실제 구간은 정확하지만 잘못된 과거 인과/구체화 설명은 새 결과에 복사되지 않는다.
            assertEquals(sourceSegment.text, row.getValue("answerQuotes").jsonArray.single().jsonPrimitive.content)
            assertFalse(row.string("originText").contains(wrongProse))
            assertEquals("PRESERVED", row.getValue("relationChecks").jsonObject.string("specificity"))
            val injected = JsonObject(observation() + ("originText" to JsonPrimitive(wrongProse)))
            assertFailsWith<IllegalArgumentException> { validate(result(injected), learner) }
        }
    }

    @Test
    fun `실제 B4 누락 판단은 보존하면서 일본어 자유 관찰 설명 없이 한국어 안내를 조립한다`() {
        // 준비: C2 원본의 과제·답안·상태·구간을 그대로 사용하며 언어 실패 자유문장은 Provider 계약에서 제외한다.
        val original = originalCases.single { it.string("caseId") == "C2" }
        val input = original.getValue("input").jsonObject
        val task = input.getValue("task").jsonObject
        val required = task.getValue("requirements").jsonArray.map { it.jsonObject.string("id") to it.jsonObject.string("text") }
        val old = original.getValue("output").jsonObject
        val observations = old.getValue("observations").jsonArray.map { raw ->
            val row = raw.jsonObject
            val relations = observation(missing = row.string("evidenceKind") == "MISSING_REQUIREMENT").getValue("relationChecks")
            JsonObject(row - setOf("originText", "learningText") + ("relationChecks" to relations))
        }
        val typed = JsonObject(old + ("observations" to JsonArray(observations)))
        val ctx = CuratedWritingFeedbackV4Policy.Context(WritingType.FREE, task.getValue("publicPromptAndConditions").jsonObject, "ko", "ja")
        val segments = CuratedWritingFeedbackV4Policy.segments(input.getValue("learnerAnswer").jsonObject.string("text"))

        // 실행
        val saved = CuratedWritingFeedbackV4Policy.validate(typed, ctx, required, segments)

        // 검증: C2 누락·빈 인용은 유지하고 설명을 잘못된 언어로 넘기거나 개인 사정을 발명하지 않는다.
        val missing = saved.getValue("observations").jsonArray[1].jsonObject
        assertEquals("UNMET", missing.string("status"))
        assertEquals("MISSING_REQUIREMENT", missing.string("evidenceKind"))
        assertTrue(missing.getValue("answerQuotes").jsonArray.isEmpty())
        assertTrue(saved.getValue("necessaryCorrections").jsonArray.isEmpty())
        saved.getValue("observations").jsonArray.forEach {
            assertTrue(Regex("[가-힣]").containsMatchIn(it.jsonObject.string("originText")))
            assertFalse(Regex("[가-힣]").containsMatchIn(it.jsonObject.string("learningText")))
        }
        assertEquals(old.getValue("observations").jsonArray.map { it.jsonObject.string("status") },
            saved.getValue("observations").jsonArray.map { it.jsonObject.string("status") })
    }

    @Test
    fun `실제로 작성한 노트북 출장과 인과 원문은 금지하지 않고 정확히 인용한다`() {
        // 준비
        val actual = "出張で使うノートパソコンを買いました。"

        // 실행
        val saved = validate(result(observation()), actual)

        // 검증: 특정 단어 목록으로 새 사실 발명을 막는 방식이 아니다.
        assertEquals(actual, saved.getValue("observations").jsonArray.single().jsonObject
            .getValue("answerQuotes").jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `관계 충돌을 MET로 숨길 수 없고 원래 일정과 현재 안내의 불일치를 보존한다`() {
        // 준비: C3 실제 현재 안내 구간을 그대로 재사용한다.
        val source = originalCases.single { it.string("caseId") == "C3" }
            .getValue("input").jsonObject.getValue("learnerAnswer").jsonObject.string("text")
        val conflict = observation(status = "UNMET", relation = "chronology" to "CONFLICT", segment = "S2")

        // 실행
        val saved = validate(result(conflict), source)
        val falseMet = JsonObject(conflict + ("status" to JsonPrimitive("MET")))

        // 검증: 동일 숫자의 존재만으로 충족을 인정하지 않는 typed 계약이다. 의미 판정 자체는 실모델로 별도 검토한다.
        val row = saved.getValue("observations").jsonArray.single().jsonObject
        assertEquals("UNMET", row.string("status"))
        assertTrue(row.string("originText").contains("시간·변경 관계"))
        assertEquals(CuratedWritingFeedbackV4Policy.segments(source)[1].text,
            row.getValue("answerQuotes").jsonArray.single().jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { validate(result(falseMet), source) }
        assertFailsWith<IllegalArgumentException> {
            validate(result(observation(status = "MET", relation = "chronology" to "UNCERTAIN")), source)
        }
        assertEquals("UNCERTAIN", validate(result(observation(status = "UNCERTAIN", relation = "chronology" to "UNCERTAIN")), source)
            .getValue("observations").jsonArray.single().jsonObject.string("status"))
    }

    @Test
    fun `선택 쉼표와 공백 표기만의 차이는 필수 오류가 아니며 선택 개선은 유지한다`() {
        // 준비: C4 원본과 실제 모델의 쉼표 replacement를 그대로 사용한다.
        val case = originalCases.single { it.string("caseId") == "C4" }
        val learner = case.getValue("input").jsonObject.getValue("learnerAnswer").jsonObject.string("text")
        val comma = case.getValue("output").jsonObject.getValue("necessaryCorrections").jsonArray.single().jsonObject.string("replacement")
        val pairs = listOf(learner to comma, "毎晩20分読みます。" to "毎晩、２０分 読みます。")

        // 실행 / 검증: 근거 없는 필수 교정은 거절하지만 뜻이 같은 선택적 표현은 남긴다.
        pairs.forEach { (original, replacement) ->
            val correction = correction(replacement)
            assertFailsWith<IllegalArgumentException> { validate(result(observation(), corrections = listOf(correction)), original) }
            val optional = JsonObject(correction - setOf("requirementId", "issueType", "sourceFactId", "formReason") +
                ("suggestionKind" to JsonPrimitive("EXPRESSION_ONLY")))
            val saved = validate(result(observation(), optional = listOf(optional)), original)
            assertEquals(1, saved.getValue("optionalAlternatives").jsonArray.size)
        }
    }

    @Test
    fun `MET 내용의 실제 오타와 활용 오류는 유용한 최소 교정을 유지한다`() {
        // 준비: 원본 정상형과 실제 오타를 대조한다.
        val original = "欠席した生徒も増っていました。"
        val corrected = correction(answer, form = "INFLECTION")

        // 실행
        val saved = validate(result(observation(), corrections = listOf(corrected)), original)

        // 검증: 오류 탐지를 비우거나 내용 상태를 임의로 바꾸지 않는다.
        assertEquals("MET", saved.getValue("observations").jsonArray.single().jsonObject.string("status"))
        val row = saved.getValue("necessaryCorrections").jsonArray.single().jsonObject
        assertEquals(original, row.string("original"))
        assertEquals(answer, row.string("replacement"))
        assertEquals("LANGUAGE_FORM", row.string("issueType"))
        assertFailsWith<IllegalArgumentException> { validate(result(observation(), corrections = listOf(corrected)), answer) }
    }

    @Test
    fun `FREE 개인 의견 요구는 명시 사실 교정 권한이 아니며 실제 선택 보충을 안내한다`() {
        // 준비: C6 편리함을 신뢰한다는 의견으로 바꾸는 원본 교정을 권한 검사에 넣는다.
        val case = originalCases.single { it.string("caseId") == "C6" }
        val original = case.getValue("input").jsonObject.getValue("learnerAnswer").jsonObject.string("text")
        val replacement = case.getValue("output").jsonObject.getValue("necessaryCorrections").jsonArray.single().jsonObject.string("replacement")
        val unmet = observation(status = "UNMET", relation = "stance" to "CONFLICT")
        val requiredChoice = listOf("C1" to "가장 신뢰하는 정보원을 자신의 의견으로 밝힐 것")
        val invented = correction(replacement, issue = "TASK_FACT_CONFLICT", fact = "T1", form = "NONE")

        // 실행 / 검증: 체크리스트와 참고 답안은 T1을 만들지 않으므로 사실 교정을 허용하지 않는다.
        assertTrue(CuratedWritingFeedbackV4Policy.taskFacts(free).isEmpty())
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV4Policy.validate(result(unmet, corrections = listOf(invented)), free,
                requiredChoice, CuratedWritingFeedbackV4Policy.segments(original))
        }
        val saved = CuratedWritingFeedbackV4Policy.validate(result(unmet), free, requiredChoice, CuratedWritingFeedbackV4Policy.segments(original))
        assertTrue(saved.getValue("necessaryCorrections").jsonArray.isEmpty())
        assertTrue(saved.getValue("observations").jsonArray.single().jsonObject.string("originText").contains(requiredChoice.single().second))
        val missing = validate(result(observation(status = "UNMET", missing = true)), original)
        assertTrue(missing.getValue("observations").jsonArray.single().jsonObject.getValue("answerQuotes").jsonArray.isEmpty())
        assertTrue(missing.toString().contains("자신의 실제 내용"))
    }

    @Test
    fun `번역 안내의 명시 사실 충돌과 FREE에 실제 제공한 사실은 고칠 수 있다`() {
        // 준비: 요구 문구가 아닌 공개 제공 사실의 권한을 사용한다.
        val original = "期限を延長できません。"
        val replacement = "期限を延長できます。"
        val contexts = listOf(context(WritingType.TRANSLATION, prompt = "기한을 연장할 수 있습니다."),
            context(WritingType.GUIDED, facts = listOf("기한을 연장할 수 있습니다.")),
            context(WritingType.FREE, facts = listOf("기한을 연장할 수 있습니다.")))

        // 실행 / 검증: FREE 선택권 보호가 실제 과제 사실 교정까지 없애지 않는다.
        contexts.forEach { ctx ->
            val correction = correction(replacement, issue = "TASK_FACT_CONFLICT", fact = "T1", form = "NONE")
            val saved = CuratedWritingFeedbackV4Policy.validate(result(observation(status = "UNMET", relation = "stance" to "CONFLICT"),
                corrections = listOf(correction)), ctx, requirements, CuratedWritingFeedbackV4Policy.segments(original))
            assertEquals("T1", saved.getValue("necessaryCorrections").jsonArray.single().jsonObject.string("sourceFactId"))
        }
    }

    @Test
    fun `누락 보충 안내는 번역 원문 제공 사실과 개인 선택을 구별한다`() {
        // 준비: 같은 누락 상태라도 채워야 할 내용의 출처는 과제 유형마다 다르다.
        val missing = result(observation(status = "UNMET", missing = true))
        val cases = listOf(
            context(WritingType.TRANSLATION) to "번역 원문에서 빠진 내용",
            context(WritingType.GUIDED, facts = listOf("토요일 오후에 진행합니다.")) to "제공된 사실과 요구에 맞게",
            free to "자신의 실제 내용으로 덧붙여",
            context(WritingType.FREE, facts = listOf("지역 도서관이 새로 문을 엽니다.")) to "주어진 사실은 과제에 맞게, 개인 선택은",
        )

        // 실행 / 검증: 원문 누락을 허구의 개인 사정으로 채우도록 안내하지 않는다.
        cases.forEach { (ctx, expected) ->
            val saved = CuratedWritingFeedbackV4Policy.validate(missing, ctx, requirements, CuratedWritingFeedbackV4Policy.segments(answer))
            val row = saved.getValue("observations").jsonArray.single().jsonObject
            assertTrue(row.string("originText").contains(expected))
            assertTrue(row.getValue("answerQuotes").jsonArray.isEmpty())
            assertTrue(saved.getValue("necessaryCorrections").jsonArray.isEmpty())
            if (ctx.writingType != WritingType.FREE) assertFalse(row.string("originText").contains("자신의 실제 내용"))
        }
    }

    @Test
    fun `한국어 주설명과 일본어 인용을 분리하고 한글 한 글자 및 일본어 문장 침범은 거절한다`() {
        // 준비: 의미와 교정은 같고 설명 언어만 다른 대조군이다.
        val original = "欠席した生徒も増っていました。"
        val baseline = correction(answer)
        val good = listOf("‘増って’의 활용을 ‘増えて’로 고쳐 증가했다는 뜻을 유지합니다.",
            "「増えて」라는 활용형으로 바꿉니다. 기존 답안의 의미는 유지합니다.")
        val bad = listOf("敬語の丁寧な表現を使用しています.", "가 敬語の丁寧な表現を使用しています。",
            "표현입니다. 敬語の丁寧な表現を使用しています。", "한국어 敬語の丁寧な表現を使用しています")

        // 실행 / 검증
        good.forEach { explanation ->
            val row = JsonObject(baseline + ("originText" to JsonPrimitive(explanation)))
            assertEquals(1, validate(result(observation(), corrections = listOf(row)), original).getValue("necessaryCorrections").jsonArray.size)
        }
        bad.forEach { explanation ->
            val row = JsonObject(baseline + ("originText" to JsonPrimitive(explanation)))
            assertFailsWith<IllegalArgumentException> { validate(result(observation(), corrections = listOf(row)), original) }
        }
        val mixed = JsonObject(baseline + ("learningText" to JsonPrimitive("活用を 바꿉니다。")))
        assertFailsWith<IllegalArgumentException> { validate(result(observation(), corrections = listOf(mixed)), original) }
    }

    @Test
    fun `저장된 언어쌍과 source ID를 입력에 명시하고 미지원 언어와 schema 기능을 추가하지 않는다`() {
        // 준비
        val reference = buildJsonObject { put("referenceAnswers", buildJsonArray { add(answer) }); put("alternativeNote", "정상 예시") }
        val parts = CuratedWritingFeedbackV4Policy.segments(answer)

        // 실행
        val prompt = Json.parseToJsonElement(CuratedWritingFeedbackV4Policy.prompt(free, reference, answer, requirements, parts)).jsonObject
        val schema = CuratedWritingFeedbackV4Policy.schema(free, requirements, parts)
        val fields = schema.getValue("properties").jsonObject.getValue("observations").jsonObject
            .getValue("items").jsonObject.getValue("properties").jsonObject

        // 검증: provider pattern 재시도와 자유 관찰 설명을 구조에서 제거하고 저장 언어를 명시한다.
        assertEquals("ko", prompt.getValue("task").jsonObject.string("originLanguage"))
        assertEquals("ja", prompt.getValue("task").jsonObject.string("learningLanguage"))
        assertFalse("referenceExamples" in prompt)
        assertFalse("originText" in fields)
        assertFalse("learningText" in fields)
        assertTrue("relationChecks" in fields)
        assertFalse(schema.toString().contains("\"pattern\""))
        assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV4Policy.Context(WritingType.FREE, free.public, "ja", "ko") }
    }

    private fun context(type: WritingType, prompt: String = "자신의 내용을 쓰세요.", facts: List<String> = emptyList()) =
        CuratedWritingFeedbackV4Policy.Context(type, buildJsonObject {
            put("originText", prompt); put("providedFacts", JsonArray(facts.map(::JsonPrimitive)))
        }, "ko", "ja")

    private fun observation(status: String = "MET", missing: Boolean = false, relation: Pair<String, String>? = null, segment: String = "S1") = buildJsonObject {
        put("requirementId", "C1"); put("status", status)
        put("evidenceKind", if (missing) "MISSING_REQUIREMENT" else "LEARNER_TEXT")
        put("segmentIds", buildJsonArray { if (!missing) add(segment) })
        put("relationChecks", buildJsonObject {
            listOf("causality", "chronology", "specificity", "stance").forEach {
                put(it, if (relation?.first == it) relation.second else if (missing) "NOT_APPLICABLE" else "PRESERVED")
            }
        })
    }

    private fun correction(replacement: String, issue: String = "LANGUAGE_FORM", fact: String = "", form: String = "SPELLING") = buildJsonObject {
        put("requirementId", "C1"); put("segmentId", "S1"); put("replacement", replacement)
        put("issueType", issue); put("sourceFactId", fact); put("formReason", form)
        put("originText", "잘못된 활용을 고쳐 원래 의미가 전달되도록 합니다.")
        put("learningText", "誤った活用を直し、元の意味を伝えます。")
    }

    private fun result(observation: JsonObject, corrections: List<JsonObject> = emptyList(), optional: List<JsonObject> = emptyList()) = buildJsonObject {
        put("observations", JsonArray(listOf(observation))); put("necessaryCorrections", JsonArray(corrections))
        put("optionalAlternatives", JsonArray(optional)); put("uncertainties", buildJsonArray {})
    }

    private fun validate(result: JsonObject, original: String = answer) = CuratedWritingFeedbackV4Policy.validate(
        result, free, requirements, CuratedWritingFeedbackV4Policy.segments(original),
    )

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
