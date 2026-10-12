package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import kotlin.test.*

class CuratedWritingFeedbackV8PolicyTest {
    private val answer = "本を読みます。まだ理由は決めていません。"
    private val requirement = "자료를 고르고 이유를 설명할 것"
    private val free = context(WritingType.FREE)
    private val requirements = listOf("C1" to requirement)

    @Test
    fun `긴 요구 서른 개의 source unit 수는 Provider schema enum 크기를 늘리지 않는다`() {
        // 준비: 원래 지원하는 checklist 개수 안에서 단위 6000개를 만든다.
        val longRequirements = (1..30).map { index ->
            "C$index" to (1..200).joinToString(" ") { "원문항목$it" }
        }
        val segments = CuratedWritingFeedbackPolicy.segments(answer)

        // 실행
        val schema = CuratedWritingFeedbackPolicy.schema(free, longRequirements, segments)
        val part = schema.getValue("properties").jsonObject.getValue("observations").jsonObject
            .getValue("items").jsonObject.getValue("properties").jsonObject.getValue("requirementParts").jsonObject
            .getValue("items").jsonObject.getValue("properties").jsonObject
        fun enumCount(value: JsonElement): Int = when (value) {
            is JsonObject -> (value["enum"] as? JsonArray)?.size.orZero() + value.filterKeys { it != "enum" }.values.sumOf(::enumCount)
            is JsonArray -> value.sumOf(::enumCount)
            else -> 0
        }

        // 검증: 범위 ID는 입력에 있고 서버가 검사하므로 중복 schema enum이 필요하지 않다.
        assertEquals(6000, longRequirements.sumOf { CuratedWritingRequirementUnits.compile(it.first, it.second).size })
        assertEquals(buildJsonObject { put("type", "string") }, part.getValue("startUnitId"))
        assertEquals(part.getValue("startUnitId"), part.getValue("endUnitId"))
        assertTrue(enumCount(schema) < 1000)
        assertTrue(schema.toString().toByteArray(Charsets.UTF_8).size < 120000)
    }

    private fun Int?.orZero() = this ?: 0

    @Test
    fun `원문 단위는 공백과 문장부호를 보존하고 의미를 자동 분해하지 않는다`() {
        // 준비 / 실행
        val source = " \t시간,\n또는 빈도와 이유를\u3000설명한다.  "
        val units = CuratedWritingRequirementUnits.compile("C2", source)

        // 검증: OR와 접속어를 특별 취급하거나 교체하지 않는다.
        assertEquals(source, units.joinToString("") { it.text })
        assertEquals(listOf("C2.U1", "C2.U2", "C2.U3", "C2.U4", "C2.U5"), units.map { it.id })
        assertEquals(" \t시간,\n", units.first().text)
        assertEquals("설명한다.  ", units.last().text)
    }

    @Test
    fun `요구 범위로 원문을 서버 조립하고 부분 누락을 실제 인용과 구분한다`() {
        // 준비: 자료 선택은 존재하지만 개인 이유는 없으며 가상의 교정을 만들지 않는다.
        val raw = output(listOf(part(1, 2), part(3, 5, "ABSENT")))
        val before = raw.toString()

        // 실행
        val saved = save(raw)

        // 검증
        assertEquals(before, raw.toString())
        val observed = saved.getValue("observations").jsonArray.single().jsonObject
        assertEquals("UNMET", observed.string("status"))
        assertEquals("LEARNER_TEXT", observed.string("evidenceKind"))
        assertEquals(requirement, observed.getValue("requirementParts").jsonArray.joinToString("") { it.jsonObject.string("requirementQuote") })
        assertTrue(observed.string("originText").contains("이유를 설명할 것"))
        assertTrue(observed.string("originText").contains("자신의 실제 내용"))
        assertTrue(saved.getValue("necessaryCorrections").jsonArray.isEmpty())
        assertEquals("curated-writing-feedback-v8", saved.string("policyVersion"))
    }

    @Test
    fun `교차 요구 미지 ID 역전 중복 누락 순서와 추가 원문 필드는 거절한다`() {
        // 준비
        val invalid = listOf(
            listOf(part(1, 2), part(2, 5)), listOf(part(1, 1), part(3, 5)),
            listOf(part(2, 5)), listOf(part(1, 4)), listOf(part(1, 5), part(1, 5)),
            listOf(part(3, 5), part(1, 2)), listOf(part(1, 0)), emptyList(),
            listOf(JsonObject(part(1, 5) + ("endUnitId" to JsonPrimitive("C2.U5")))),
            listOf(JsonObject(part(1, 5) + ("endUnitId" to JsonPrimitive("C1.U99")))),
            listOf(JsonObject(part(1, 5) + ("requirementQuote" to JsonPrimitive(requirement)))),
        )

        // 실행 / 검증
        invalid.forEach { assertFailsWith<IllegalArgumentException> { save(output(it)) } }
        val duplicate = output(listOf(part(1, 5)))
        val rows = duplicate.getValue("observations").jsonArray
        assertFailsWith<IllegalArgumentException> {
            save(JsonObject(duplicate + ("observations" to JsonArray(rows + rows))))
        }
    }

    @Test
    fun `source-unit 전환도 허위 인용 불일치 관계와 누락 인용을 허용하지 않는다`() {
        // 준비
        val fake = part(1, 5, quote = "存在しない引用です。")
        val absentWithEvidence = JsonObject(part(1, 5) + ("status" to JsonPrimitive("ABSENT")))
        val conflict = output(listOf(part(1, 5)), relation = "CONFLICT")

        // 실행 / 검증
        listOf(output(listOf(fake)), output(listOf(absentWithEvidence)), conflict).forEach {
            assertFailsWith<IllegalArgumentException> { save(it) }
        }
    }

    @Test
    fun `GUIDED 명시 조건 권한은 제공 사실과 구분하고 실제 충돌만 최소 교정한다`() {
        // 준비
        val ctx = context(WritingType.GUIDED, intents = listOf("배식 방식만으로 감소를 설명할 수 없다고 밝힌다."))
        val text = "配食方法だけで説明できます。別の事実は正しいです。"
        val req = listOf("C1" to "배식 방식만으로 설명할 수 없다고 밝힌다")
        val raw = output(listOf(part(1, 6, "CONFLICT", quote = "説明できます。", conditions = listOf("P1"))),
            relation = "CONFLICT", correction = correction("TASK_CONDITION_CONFLICT", "説明できます。", "説明できません。", authority = "P1"))

        // 실행
        val saved = CuratedWritingFeedbackPolicy.validate(raw, ctx, req, CuratedWritingFeedbackPolicy.segments(text))

        // 검증
        val correction = saved.getValue("necessaryCorrections").jsonArray.single().jsonObject
        assertEquals("TASK_CONDITION_CONFLICT", correction.string("issueType"))
        assertEquals("P1", correction.string("sourceAuthorityId"))
        assertEquals("", correction.string("sourceFactId"))
        assertEquals("配食方法だけで説明できません。", correction.string("replacement"))
        assertTrue(correction.string("originText").contains("과제에 명시된 조건"))
        assertFalse(correction.string("originText").contains("명시된 사실"))
        assertEquals(emptyList(), CuratedWritingFeedbackPolicy.taskFacts(ctx))
    }

    @Test
    fun `FREE 지시에도 P 권한은 없고 GUIDED 개인 선택 누락은 교정 권한이 아니다`() {
        // 준비: 같은 선택 지시라도 P는 제공된 선호값이 아니다.
        val intents = listOf("자신의 입장을 고르고 이유를 설명한다.")
        val ctxFree = context(WritingType.FREE, intents = intents)
        val ctxGuided = context(WritingType.GUIDED, intents = intents)
        assertTrue(CuratedWritingFeedbackPolicy.taskConditions(ctxFree).isEmpty())
        val invented = correction("TASK_CONDITION_CONFLICT", "まだ理由は決めていません。", "家族を信頼しています。", authority = "P1")
        val absent = output(listOf(part(1, 5, "ABSENT", conditions = listOf("P1"))), correction = invented)

        // 실행 / 검증: ABSENT 선택을 실제 모순처럼 교정하거나 FREE에 P를 주입하지 않는다.
        listOf(ctxFree, ctxGuided).forEach { ctx ->
            assertFailsWith<IllegalArgumentException> {
                CuratedWritingFeedbackPolicy.validate(absent, ctx, requirements, CuratedWritingFeedbackPolicy.segments(answer))
            }
        }
        val validText = "本を選びます。内容を確かめられるからです。"
        val validParts = listOf(
            part(1, 2, quote = "本を選びます。", conditions = listOf("P1")),
            JsonObject(part(3, 5, conditions = listOf("P1")) + ("evidence" to buildJsonArray { add(buildJsonObject {
                put("segmentId", "S2"); put("quote", "内容を確かめられるからです。")
            }) })),
        )
        val accepted = CuratedWritingFeedbackPolicy.validate(output(validParts), ctxGuided, requirements, CuratedWritingFeedbackPolicy.segments(validText))
        assertTrue(accepted.getValue("necessaryCorrections").jsonArray.isEmpty())
        // 이 합성 판정은 선택 내용의 실제 의미를 증명하지 않는다. 실모델 수용은 별도로 확인한다.
        assertTrue(CuratedWritingFeedbackPolicy.instructions.contains("prescribes no value"))
    }

    @Test
    fun `조건 교정은 연결된 충돌 span 안에서만 허용하고 권한 혼용을 거절한다`() {
        // 준비
        val ctx = context(WritingType.GUIDED, intents = listOf("첫 조건을 보존한다.", "둘째 조건을 보존한다."))
        val p = part(1, 5, "CONFLICT", quote = "本を読みます。", conditions = listOf("P1", "P2"))
        val invalid = listOf(
            correction("TASK_CONDITION_CONFLICT", "まだ理由は決めていません。", "理由があります。", authority = "P1"),
            correction("TASK_CONDITION_CONFLICT", "本を読みます。", "本を読みません。", authority = "P99"),
            correction("TASK_CONDITION_CONFLICT", "本を読みます。", "本を読みません。", authority = "P1", fact = "T1"),
            correction("LANGUAGE_FORM", "本を読みます。", "本を読みません。", authority = "P1"),
            correction("TASK_FACT_CONFLICT", "本を読みます。", "本を読みません。", fact = "P1"),
        )

        // 실행 / 검증
        invalid.forEach { c -> assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackPolicy.validate(output(listOf(p), "CONFLICT", c), ctx, requirements, CuratedWritingFeedbackPolicy.segments(answer))
        } }
        val unlinked = JsonObject(p + ("taskConditionIds" to buildJsonArray { add("P2") }))
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackPolicy.validate(output(listOf(unlinked), "CONFLICT", correction(
                "TASK_CONDITION_CONFLICT", "本を読みます。", "本を読みません。", authority = "P1")), ctx, requirements,
                CuratedWritingFeedbackPolicy.segments(answer))
        }
    }

    @Test
    fun `v8에서도 유용한 선택 제안과 실제 형태 교정은 유지하고 숫자 표기만 필수 교정하지 않는다`() {
        // 준비
        val req = listOf("C1" to "시간을 기록한다")
        val text = "一週間本を読みました。"
        val p = part(1, 2, quote = text)
        val notation = output(listOf(p), correction = correction("LANGUAGE_FORM", "一週間", "1週間"))
        val typo = output(listOf(part(1, 2, quote = "増っていました。")), correction = correction(
            "LANGUAGE_FORM", "増っていました", "増えていました"))

        // 실행 / 검증
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackPolicy.validate(notation, free, req, CuratedWritingFeedbackPolicy.segments(text))
        }
        val saved = CuratedWritingFeedbackPolicy.validate(typo, free, req, CuratedWritingFeedbackPolicy.segments("増っていました。"))
        assertEquals("増えていました。", saved.getValue("necessaryCorrections").jsonArray.single().jsonObject.string("replacement"))
    }

    @Test
    fun `기존 실패 raw는 보존하고 새 범위 표현만으로 유용한 일곱 회귀 응답을 전달한다`() {
        // 준비: 의미 합격의 새 증거가 아닌 노출된 원본의 명시적 계약 변환 replay다.
        val fixtures = Json.parseToJsonElement(requireNotNull(javaClass.getResource(
            "/contracts/curated-writing-feedback-v7-contract-repair.json")).readText(Charsets.UTF_8)).jsonArray

        fixtures.forEach { rawFixture ->
            val fixture = rawFixture.jsonObject
            val task = fixture.getValue("input").jsonObject.getValue("task").jsonObject
            val ctx = CuratedWritingFeedbackPolicy.Context(WritingType.valueOf(task.string("writingType")),
                task.getValue("publicPromptAndConditions").jsonObject, "ko", "ja")
            val req = task.getValue("requirements").jsonArray.map { it.jsonObject.string("id") to it.jsonObject.string("text") }
            val text = fixture.getValue("input").jsonObject.getValue("learnerAnswer").jsonObject.string("text")
            val original = fixture.getValue("outputText").jsonObject
            val originalBefore = original.toString()
            val converted = convertExposedReplay(fixture.string("caseId"), original, req)
            val segments = CuratedWritingFeedbackPolicy.segments(text)

            // 실행: 옛 실패의 판정을 바꾸지 않으며 새 ID 계약을 별도로 검사한다.
            assertFailsWith<IllegalArgumentException> {
                CuratedWritingFeedbackV7Policy.validate(original, CuratedWritingFeedbackV7Policy.Context(ctx.writingType, ctx.public, "ko", "ja"), req, segments)
            }
            val saved = try {
                CuratedWritingFeedbackPolicy.validate(converted, ctx, req, segments)
            } catch (cause: IllegalArgumentException) {
                throw IllegalArgumentException("Contract replay failed: ${fixture.string("caseId")}", cause)
            }

            // 검증: learner/status/evidence/patch는 그대로이고 원문 요구는 서버가 모두 복원했다.
            assertEquals(originalBefore, original.toString())
            assertEquals("curated-writing-feedback-v8", saved.string("policyVersion"))
            val rows = saved.getValue("observations").jsonArray.map { it.jsonObject }
            rows.forEach { o -> assertEquals(req.toMap().getValue(o.string("requirementId")),
                o.getValue("requirementParts").jsonArray.joinToString("") { it.jsonObject.string("requirementQuote") }) }
            original.getValue("observations").jsonArray.zip(rows).forEach { (a, b) ->
                a.jsonObject.getValue("requirementParts").jsonArray.zip(b.getValue("requirementParts").jsonArray).forEach { (x, y) ->
                    assertEquals(x.jsonObject.getValue("evidence"), y.jsonObject.getValue("evidence"))
                    assertEquals(x.jsonObject.getValue("status"), y.jsonObject.getValue("status"))
                }
            }
            original.getValue("necessaryCorrections").jsonArray.zip(saved.getValue("necessaryCorrections").jsonArray).forEach { (a, b) ->
                assertEquals(a.jsonObject.getValue("patch"), b.jsonObject.getValue("patch"))
                assertEquals(a.jsonObject.getValue("learningText"), b.jsonObject.getValue("learningText"))
            }
        }
    }

    private fun convertExposedReplay(caseId: String, raw: JsonObject, req: List<Pair<String, String>>): JsonObject {
        // 명시적 QA 표현 변환이다. 모델 출력의 사후 성공 변환으로 사용하지 않는다.
        val conditionMapping = when (caseId) {
            "reg-f03-original-b51-negation-error-with-valid-spelling", "reg-f03-actual-futtemashita-typo" ->
                mapOf("C3" to listOf("P1"), "C4" to listOf("P2"), "C5" to listOf("P3"), "C6" to listOf("P4"))
            "reg-gen-g-g1" -> mapOf("C2" to listOf("P1", "P2"), "C3" to listOf("P3"), "C4" to listOf("P4"))
            else -> emptyMap()
        }
        val rows = raw.getValue("observations").jsonArray.map { value ->
            val o = value.jsonObject
            val id = o.string("requirementId")
            val units = CuratedWritingRequirementUnits.compile(id, req.toMap().getValue(id))
            val oldParts = o.getValue("requirementParts").jsonArray
            val breaks = when {
                caseId == "reg-f03-actual-futtemashita-typo" && id == "C2" -> listOf("결석")
                caseId == "reg-f03-actual-futtemashita-typo" && id == "C4" -> listOf("추가")
                caseId in setOf("reg-gen-g-t1a", "reg-gen-g-t1e") && id == "C3" -> listOf("사전")
                caseId == "reg-gen-g-g1" && id == "C1" -> listOf("중앙도서관", "열릴")
                caseId == "reg-gen-g-g1" && id == "C2" -> listOf("장소를", "시간은")
                caseId == "reg-gen-g-g1" && id == "C3" -> listOf("입구에서")
                caseId == "reg-gen-g-g1" && id == "C4" -> listOf("변경")
                caseId == "reg-gen-g-f2a" && id == "C2" -> listOf("믿는", "설명할")
                caseId == "repeat-c1" && id == "C2" -> listOf("요청을")
                else -> emptyList()
            }
            val starts = if (breaks.isEmpty() && oldParts.size > 1) {
                // 원래 정확히 표현한 단위 경계는 그 원문 위치로 대응한다.
                var cursor = 0
                oldParts.map { p ->
                    val text = p.jsonObject.string("requirementQuote").trim()
                    val first = units.indexOfFirst { it.text.trim().startsWith(text.substringBefore(' ')) && units.indexOf(it) >= cursor }
                    require(first >= 0); cursor = first + 1; first
                }
            } else listOf(0) + breaks.map { start -> units.indexOfFirst { it.text.trim() == start }.also { require(it > 0) } }
            require(starts.size == oldParts.size)
            JsonObject(o + ("requirementParts" to JsonArray(oldParts.mapIndexed { i, p ->
                JsonObject(p.jsonObject - "requirementQuote" + mapOf(
                    "startUnitId" to JsonPrimitive(units[starts[i]].id),
                    "endUnitId" to JsonPrimitive(units[(starts.getOrNull(i + 1) ?: units.size) - 1].id),
                    "taskConditionIds" to JsonArray(conditionMapping[id].orEmpty().map(::JsonPrimitive)),
                ))
            })))
        }
        val corrections = raw.getValue("necessaryCorrections").jsonArray.map { value ->
            val c = value.jsonObject
            if (caseId == "reg-f03-original-b51-negation-error-with-valid-spelling") JsonObject(c + mapOf(
                "issueType" to JsonPrimitive("TASK_CONDITION_CONFLICT"), "sourceFactId" to JsonPrimitive(""),
                "sourceAuthorityId" to JsonPrimitive("P1"),
            )) else JsonObject(c + ("sourceAuthorityId" to JsonPrimitive("")))
        }
        return JsonObject(raw + mapOf("observations" to JsonArray(rows), "necessaryCorrections" to JsonArray(corrections)))
    }

    private fun context(type: WritingType, intents: List<String> = emptyList()) = CuratedWritingFeedbackPolicy.Context(type,
        buildJsonObject { put("requiredIntents", JsonArray(intents.map(::JsonPrimitive))) }, "ko", "ja")
    private fun part(start: Int, end: Int, status: String = "PRESENT", quote: String = "本を読みます。", conditions: List<String> = emptyList()) = buildJsonObject {
        put("startUnitId", "C1.U$start"); put("endUnitId", "C1.U$end"); put("status", status)
        put("evidence", buildJsonArray { if (status != "ABSENT") add(buildJsonObject { put("segmentId", "S1"); put("quote", quote) }) })
        put("taskFactIds", buildJsonArray {}); put("taskConditionIds", JsonArray(conditions.map(::JsonPrimitive)))
    }
    private fun correction(type: String, from: String, to: String, fact: String = "", authority: String = "") = buildJsonObject {
        put("requirementId", "C1"); put("segmentId", "S1"); put("patch", buildJsonObject { put("original", from); put("replacement", to) })
        put("issueType", type); put("sourceFactId", fact); put("sourceAuthorityId", authority)
        put("formReason", if (type == "LANGUAGE_FORM") "INFLECTION" else "NONE")
        put("learningText", "元の表現と指定された内容を比較し、この部分だけを直します。")
    }
    private fun output(parts: List<JsonObject>, relation: String = "PRESERVED", correction: JsonObject? = null) = buildJsonObject {
        put("observations", buildJsonArray { add(buildJsonObject {
            put("requirementId", "C1"); put("requirementParts", JsonArray(parts))
            put("relationChecks", buildJsonObject { listOf("causality", "chronology", "specificity", "stance").forEach { put(it, relation) } })
        }) })
        put("necessaryCorrections", buildJsonArray { correction?.let { add(it) } })
        put("optionalAlternatives", buildJsonArray {}); put("uncertainties", buildJsonArray {})
    }
    private fun save(raw: JsonObject) = CuratedWritingFeedbackPolicy.validate(raw, free, requirements, CuratedWritingFeedbackPolicy.segments(answer))
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
