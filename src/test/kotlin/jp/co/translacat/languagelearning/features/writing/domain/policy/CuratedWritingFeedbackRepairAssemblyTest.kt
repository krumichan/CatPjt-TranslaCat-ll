package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.CuratedWritingFeedbackTestPayload
import kotlinx.serialization.json.*
import kotlin.test.*

class CuratedWritingFeedbackRepairAssemblyTest {
    private val source = "  지난 토요일 오후에 지역 도서관에서 친구와 함께 워크숍에 참가했고 완성한 화분을 집에 가져갔다는 내용을 포함할 것  "
    private val answer = "先週の土曜日の午後、地域の図書館のワークショップに友達と参加しました。小さな植木鉢を作りました。"

    @Test
    fun `열 개 이상의 원문 단위도 JSON 키 순서와 무관하게 참석과 귀가 누락을 보존한다`() {
        // 준비: U10이 U2보다 앞서는 문자열 순서와 뒤집힌 응답 순서 모두 정식 원문 순서가 아니다.
        val case = guidedCase()
        val units = CuratedWritingRequirementUnits.compile("C1", source)
        assertTrue(units.size > 12)
        val ends = JsonObject(units.reversed().associate { unit ->
            unit.id to when (unit.id) {
                "C1.U9" -> end("PRESENT", "O1")
                units.last().id -> end("ABSENT")
                else -> JsonNull
            }
        })
        val draft = withLinks(withEnds(response(case), ends), mapOf(
            "T1" to listOf("C1.U6", "C1.U12"),
            "P1" to listOf("C1.U6", "C1.U12"),
        ))

        // 실행: 서버 조립 뒤 기존 전체 계약과 독립 UNMET 판정을 함께 검사한다.
        val assembled = assemble(draft, case)
        val checked = validate(draft, case, "UNMET")

        // 검증: 친구 참석 근거를 지우거나 귀가를 발명하지 않고 공백까지 원문 전체를 보존한다.
        val row = assembled.getValue("observations").jsonObject.getValue("C1").jsonObject
        val assignments = row.getValue("unitAssignments").jsonObject
        assertEquals(units.map { it.id }, assignments.keys.toList())
        assertEquals(units.mapIndexed { index, _ -> if (index < 9) "C1.S1" else "C1.S2" },
            assignments.values.map { it.jsonPrimitive.content })
        val parts = row.getValue("parts").jsonArray.map { it.jsonObject }
        assertEquals(listOf("PRESENT", "ABSENT"), parts.map { it.getValue("status").jsonPrimitive.content })
        assertEquals(listOf("O1"), parts.first().getValue("answerObservationIds").jsonArray.strings())
        assertTrue(parts.last().getValue("answerObservationIds").jsonArray.isEmpty())
        assertEquals(draft.getValue("answerObservations"), assembled.getValue("answerObservations"))
        val observation = checked.getValue("observations").jsonArray.single().jsonObject
        assertEquals("UNMET", observation.getValue("status").jsonPrimitive.content)
        val restored = observation.getValue("requirementParts").jsonArray.map { it.jsonObject }
        assertEquals(source, restored.joinToString("") { it.getValue("requirementQuote").jsonPrimitive.content })
        assertEquals(units.take(9).joinToString("") { it.text },
            restored.first().getValue("requirementQuote").jsonPrimitive.content)
    }

    @Test
    fun `마지막 끝점이나 원문 키가 빠져도 서버가 원문 범위를 채워 성공시키지 않는다`() {
        // 준비: 마지막 끝점 null, 원문 키 누락, 다른 요구의 단위를 각각 만든다.
        val case = guidedCase()
        val draft = response(case)
        val ends = row(draft).getValue("unitEnds").jsonObject
        val last = CuratedWritingRequirementUnits.compile("C1", source).last().id
        val malformed = listOf(
            JsonObject(ends + (last to JsonNull)),
            JsonObject(ends - "C1.U10"),
            JsonObject(ends + ("C2.U1" to JsonNull)),
        )

        // 실행·검증: 보정하지 않고 각 잘못된 경계 입력을 거절한다.
        malformed.forEachIndexed { index, value ->
            val failure = assertFailsWith<IllegalArgumentException>("boundary case $index") {
                assemble(withEnds(draft, value), case)
            }
            assertEquals("WRITING_FEEDBACK_REPAIR_ASSEMBLY_INVALID", failure.message)
        }
    }

    @Test
    fun `권한 누락 빈 배열 중복 및 외부 단위를 임의 연결로 복구하지 않는다`() {
        // 준비: 각 권한은 실제 원문 위치를 적어도 하나 가져야 한다.
        val case = guidedCase()
        val draft = response(case)
        val valid = mapOf("T1" to listOf("C1.U1"), "P1" to listOf("C1.U1"))
        val malformed = listOf(
            valid - "T1",
            valid + ("P2" to listOf("C1.U1")),
            valid + ("T1" to emptyList()),
            valid + ("T1" to listOf("C1.U1", "C1.U1")),
            valid + ("T1" to listOf("C2.U1")),
        )

        // 실행·검증: 잘못된 링크를 버리거나 다른 부분으로 옮겨 coverage를 만들지 않는다.
        malformed.forEachIndexed { index, links ->
            assertFailsWith<IllegalArgumentException>("authority case $index") {
                assemble(withLinks(draft, links), case)
            }
        }
    }

    @Test
    fun `한 권한의 여러 원문 위치는 같은 부분에서 중복되지 않고 누락 부분에도 남는다`() {
        // 준비: T/P 모두 참석 부분의 두 위치와 귀가 누락 부분의 한 위치를 가리킨다.
        val case = guidedCase()
        val draft = withLinks(split(response(case), "ABSENT"), mapOf(
            "T1" to listOf("C1.U1", "C1.U6", "C1.U12"),
            "P1" to listOf("C1.U1", "C1.U6", "C1.U12"),
        ))

        // 실행: source-unit 관계를 연속 부분으로 조립한다.
        val assembled = assemble(draft, case)
        val checked = validate(draft, case, "UNMET")

        // 검증: 권한은 학습자 증거와 별개이므로 ABSENT에도 보존되고 같은 부분에는 한 번만 붙는다.
        val parts = assembled.getValue("observations").jsonObject.getValue("C1").jsonObject
            .getValue("parts").jsonArray.map { it.jsonObject }
        parts.forEach { part ->
            assertEquals(listOf("T1"), part.getValue("taskFactIds").jsonArray.strings())
            assertEquals(listOf("P1"), part.getValue("taskConditionIds").jsonArray.strings())
        }
        assertTrue(parts.last().getValue("answerObservationIds").jsonArray.isEmpty())
        assertEquals("UNMET", checked.getValue("observations").jsonArray.single().jsonObject
            .getValue("status").jsonPrimitive.content)
    }

    @Test
    fun `조립 성공은 의미 불일치나 발명된 인용 및 평가자 지시를 수용하지 않는다`() {
        // 준비: 구조상 조립 가능한 거짓 MET와 실제 답안에 없는 관찰을 각각 준비한다.
        val case = guidedCase()
        val draft = response(case)
        val claim = draft.getValue("answerObservations").jsonArray.single().jsonObject
        val invented = withClaims(draft, listOf(JsonObject(claim +
            ("quote" to JsonPrimitive("植木鉢を家に持って帰りました。")))))
        val instruction = withClaims(draft, listOf(JsonObject(claim +
            ("kind" to JsonPrimitive("INSTRUCTION")))))

        // 실행·검증: 조립은 의미 판정을 덮어쓰지 않으며 후속 원검증기가 각각 차단한다.
        val disagreement = assertFailsWith<IllegalArgumentException> { validate(draft, case, "UNMET") }
        assertEquals("WRITING_FEEDBACK_INDEPENDENT_DISAGREEMENT", disagreement.message)
        assertFailsWith<IllegalArgumentException> { validate(invented, case, "MET") }
        assertFailsWith<IllegalArgumentException> { validate(instruction, case, "MET") }
    }

    @Test
    fun `실제 충돌 교정은 해당 충돌 부분의 T 권한으로만 허용한다`() {
        // 준비: 참석은 PRESENT지만 화분을 도서관에 두었다는 진술은 귀가 조건과 충돌한다.
        val case = guidedCase(answer + "完成した植木鉢は図書館に置いたままです。")
        val base = split(response(case), "CONFLICT", "O2")
        val firstClaim = base.getValue("answerObservations").jsonArray.single().jsonObject
        val conflictClaim = buildJsonObject {
            put("id", "O2"); put("segmentId", "S3")
            put("quote", "完成した植木鉢は図書館に置いたままです。")
            put("meaning", "完成した植木鉢を図書館に置いたままである。")
            put("kind", "STATE"); put("modality", "AFFIRMED")
        }
        val correction = correction("S3", "図書館に置いたままです", "家に持ち帰りました",
            "提示された事実T1では持ち帰ったため、置いたままという記述を直します。")
        val draft = withCorrection(withLinks(withClaims(base, listOf(firstClaim, conflictClaim)), mapOf(
            "T1" to listOf("C1.U6", "C1.U12"), "P1" to listOf("C1.U6", "C1.U12"),
        )), correction)

        // 실행: 유효한 T 교정과 T/P 혼합 및 다른 부분에만 T를 붙인 교정을 대조한다.
        val checked = validate(draft, case, "UNMET")
        val mixedAuthority = withCorrection(draft,
            JsonObject(correction + ("sourceAuthorityId" to JsonPrimitive("P1"))))
        val wrongPart = withLinks(draft, mapOf(
            "T1" to listOf("C1.U6"), "P1" to listOf("C1.U6", "C1.U12"),
        ))

        // 검증: 올바른 국소 교정은 보존하며 잘못된 권한 조합과 배치는 기존 검증기가 거절한다.
        val saved = checked.getValue("necessaryCorrections").jsonArray.single().jsonObject
        assertEquals("完成した植木鉢は家に持ち帰りました。", saved.getValue("replacement").jsonPrimitive.content)
        assertEquals(correction.getValue("patch"), saved.getValue("patch"))
        assertEquals("T1", saved.getValue("sourceFactId").jsonPrimitive.content)
        assertEquals("", saved.getValue("sourceAuthorityId").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { validate(mixedAuthority, case, "UNMET") }
        assertFailsWith<IllegalArgumentException> { validate(wrongPart, case, "UNMET") }
    }

    @Test
    fun `일본어 설명의 한국어 인용을 제거한 실제 교정은 보존하고 혼합 언어는 차단한다`() {
        // 준비: T02와 같은 오전 번역을 오후로 고치는 국소 교정이다.
        val case = translationCase()
        val base = response(case)
        val last = CuratedWritingRequirementUnits.compile("C1", case.requirements.single().second).last().id
        val ends = row(base).getValue("unitEnds").jsonObject
        val conflict = withRelations(withEnds(base, JsonObject(ends + (last to end("CONFLICT", "O1")))), "CONFLICT")
        val correction = correction("S1", "午前の別の時間", "午後の別の時間",
            "原文T1では午後への変更を問い合わせています。午前では時間帯が違うため、午後に直します。")
        val draft = withCorrection(conflict, correction)

        // 실행: 전부 일본어인 설명과 따옴표 내부·설명 말미·replacement의 한국어를 각각 검사한다.
        val checked = validate(draft, case, "UNMET")
        val koreanQuote = withCorrection(draft, JsonObject(correction +
            ("learningText" to JsonPrimitive("原文の「오후로」は午後を表すため、午後に直します。"))))
        val koreanEnding = withCorrection(draft, JsonObject(correction +
            ("learningText" to JsonPrimitive("原文では午後への変更なので、午後に直します。 오후"))))
        val koreanPatch = withCorrection(draft, JsonObject(correction + ("patch" to buildJsonObject {
            put("original", "午前の別の時間"); put("replacement", "오후の別の時間")
        })))

        // 검증: 설명을 삭제하거나 문자를 사후 제거하지 않고 실제 교정을 유지한 결과만 수용한다.
        val saved = checked.getValue("necessaryCorrections").jsonArray.single().jsonObject
        assertEquals("病院に予約を午後の別の時間に変更できるか問い合わせました。",
            saved.getValue("replacement").jsonPrimitive.content)
        assertEquals(correction.getValue("learningText"), saved.getValue("learningText"))
        for (invalid in listOf(koreanQuote, koreanEnding, koreanPatch)) {
            assertFailsWith<IllegalArgumentException> { validate(invalid, case, "UNMET") }
        }
    }

    @Test
    fun `복구 schema는 모든 권한과 최종 경계를 요구하고 교정 권한 조합을 분리한다`() {
        // 준비: 제공 사실과 명시 조건이 모두 있는 GUIDED schema를 사용한다.
        val case = guidedCase()
        val schema = CuratedWritingFeedbackV12Policy.repairSchema(case.context, case.requirements, case.segments)
        val properties = schema.getValue("properties").jsonObject
        val authorities = properties.getValue("authorityLinks").jsonObject
        val ends = properties.getValue("observations").jsonObject.getValue("properties").jsonObject
            .getValue("C1").jsonObject.getValue("properties").jsonObject.getValue("unitEnds").jsonObject
        val units = CuratedWritingRequirementUnits.compile("C1", source)

        // 실행·검증: Provider에 보내는 schema도 누락과 잘못된 권한 조합을 허용하지 않는다.
        assertEquals(setOf("T1", "P1"), authorities.getValue("required").jsonArray.strings().toSet())
        authorities.getValue("properties").jsonObject.values.forEach {
            assertEquals(1, it.jsonObject.getValue("minItems").jsonPrimitive.int)
        }
        assertEquals(units.map { it.id }, ends.getValue("required").jsonArray.strings())
        val endFields = ends.getValue("properties").jsonObject
        assertFalse("anyOf" in endFields.getValue(units.last().id).jsonObject)
        assertTrue("anyOf" in endFields.getValue(units.first().id).jsonObject)
        val families = properties.getValue("necessaryCorrections").jsonObject.getValue("items").jsonObject
            .getValue("anyOf").jsonArray.map { it.jsonObject.getValue("properties").jsonObject }
        val fact = families.single { it.getValue("issueType").jsonObject.getValue("enum").jsonArray.strings() ==
            listOf("TASK_FACT_CONFLICT") }
        val condition = families.single { it.getValue("issueType").jsonObject.getValue("enum").jsonArray.strings() ==
            listOf("TASK_CONDITION_CONFLICT") }
        assertEquals(listOf("T1"), fact.getValue("sourceFactId").jsonObject.getValue("enum").jsonArray.strings())
        assertEquals(listOf(""), fact.getValue("sourceAuthorityId").jsonObject.getValue("enum").jsonArray.strings())
        assertEquals(listOf(""), condition.getValue("sourceFactId").jsonObject.getValue("enum").jsonArray.strings())
        assertEquals(listOf("P1"), condition.getValue("sourceAuthorityId").jsonObject.getValue("enum").jsonArray.strings())
        assertEquals(listOf("NONE"), fact.getValue("formReason").jsonObject.getValue("enum").jsonArray.strings())
        val pattern = Regex(fact.getValue("learningText").jsonObject.getValue("pattern").jsonPrimitive.content)
        assertTrue(pattern.matches("原文T1では午後を指定しています。"))
        assertFalse(pattern.matches("原文の「오후로」は午後を示します。"))
    }

    @Test
    fun `여섯 요구와 다섯 문장 복구 schema는 참조를 해석하고 Provider 구조 한계를 지킨다`() {
        // 준비: 여러 요구와 다섯 문장으로 enum 및 source-unit 구조가 커진 응답 계약을 만든다.
        val base = guidedCase()
        val case = base.copy(
            requirements = (1..6).map { "C$it" to source },
            answer = answer + "講師の説明は分かりやすかったです。楽しかったです。また参加したいです。",
        )
        val schema = CuratedWritingFeedbackV12Policy.repairSchema(case.context, case.requirements, case.segments)
        var properties = 0
        var enumValues = 0
        val references = mutableSetOf<String>()
        fun inspect(value: JsonElement) {
            when (value) {
                is JsonObject -> {
                    properties += value["properties"]?.jsonObject?.size ?: 0
                    enumValues += value["enum"]?.jsonArray?.size ?: 0
                    value["\$ref"]?.jsonPrimitive?.content?.let(references::add)
                    value.values.forEach(::inspect)
                }
                is JsonArray -> value.forEach(::inspect)
                else -> Unit
            }
        }

        // 실행: 모든 참조를 실제 정의에 연결해 object 깊이도 계산한다.
        inspect(schema)
        val definitions = schema.getValue("\$defs").jsonObject
        fun depth(value: JsonObject, current: Int = 0): Int {
            val reference = value["\$ref"]?.jsonPrimitive?.content
            if (reference != null) {
                assertTrue(reference.startsWith("#/\$defs/"))
                return depth(definitions.getValue(reference.removePrefix("#/\$defs/")).jsonObject, current)
            }
            val next = current + if (value["type"]?.jsonPrimitive?.content == "object") 1 else 0
            val children = value["properties"]?.jsonObject?.values.orEmpty() +
                value["anyOf"]?.jsonArray.orEmpty() + listOfNotNull(value["items"])
            return maxOf(next, children.maxOfOrNull { depth(it.jsonObject, next) } ?: next)
        }
        val objectDepth = depth(schema)

        // 검증: 미해결 참조나 확장으로 인해 strict schema 요청 자체가 거절되는 회귀를 막는다.
        assertEquals(5, case.segments.size)
        assertTrue(references.isNotEmpty())
        references.forEach { assertTrue(it.removePrefix("#/\$defs/") in definitions) }
        assertTrue(properties <= 5000, "object properties: $properties")
        assertTrue(enumValues <= 1000, "enum values: $enumValues")
        assertTrue(objectDepth <= 10, "object depth: $objectDepth")
    }

    private data class Case(
        val context: CuratedWritingFeedbackPolicy.Context,
        val requirements: List<Pair<String, String>>,
        val answer: String,
    ) {
        val segments = CuratedWritingFeedbackPolicy.segments(answer)
    }

    private fun guidedCase(text: String = answer) = Case(
        CuratedWritingFeedbackPolicy.Context(WritingType.GUIDED, buildJsonObject {
            put("originText", "친구와 워크숍에 참가하고 완성한 화분을 집에 가져간 경험을 쓰세요.")
            put("providedFacts", buildJsonArray { add("친구와 워크숍에 참가했고 완성한 화분을 집에 가져갔다.") })
            put("requiredIntents", buildJsonArray { add("참가한 일과 화분을 가져간 일을 함께 포함하기") })
        }, "ko", "ja"), listOf("C1" to source), text,
    )

    private fun translationCase() = Case(
        CuratedWritingFeedbackPolicy.Context(WritingType.TRANSLATION, buildJsonObject {
            put("originText", "병원에 예약을 오후로 바꿀 수 있는지 문의했습니다.")
            put("providedFacts", buildJsonArray {})
            put("requiredIntents", buildJsonArray {})
        }, "ko", "ja"),
        listOf("C1" to "병원에 예약을 오후로 바꿀 수 있는지 문의한 사실을 옮길 것"),
        "病院に予約を午前の別の時間に変更できるか問い合わせました。",
    )

    private fun response(case: Case): JsonObject {
        val reference = buildJsonObject {
            put("referenceAnswers", buildJsonArray {}); put("alternativeNote", "")
        }
        val input = Json.parseToJsonElement(CuratedWritingFeedbackV11Policy.prompt(
            case.context, reference, case.answer, case.requirements, case.segments,
        )).jsonObject
        return CuratedWritingFeedbackTestPayload.repairResponse(input)
    }

    private fun end(status: String, vararg evidence: String) = buildJsonObject {
        put("status", status)
        put("answerObservationIds", JsonArray(evidence.map(::JsonPrimitive)))
    }

    private fun row(root: JsonObject) = root.getValue("observations").jsonObject.getValue("C1").jsonObject

    private fun withEnds(root: JsonObject, ends: JsonObject) = JsonObject(root +
        ("observations" to buildJsonObject {
            put("C1", JsonObject(row(root) + ("unitEnds" to ends)))
        }))

    private fun withLinks(root: JsonObject, links: Map<String, List<String>>) = JsonObject(root +
        ("authorityLinks" to JsonObject(links.mapValues { JsonArray(it.value.map(::JsonPrimitive)) })))

    private fun withClaims(root: JsonObject, claims: List<JsonObject>) = JsonObject(root +
        ("answerObservations" to JsonArray(claims)))

    private fun withCorrection(root: JsonObject, correction: JsonObject) = JsonObject(root +
        ("necessaryCorrections" to JsonArray(listOf(correction))))

    private fun withRelations(root: JsonObject, state: String): JsonObject {
        val relations = row(root).getValue("relationChecks").jsonObject
        return JsonObject(root + ("observations" to buildJsonObject {
            put("C1", JsonObject(row(root) + ("relationChecks" to
                JsonObject(relations + ("stance" to JsonPrimitive(state))))))
        }))
    }

    private fun split(root: JsonObject, secondStatus: String, vararg evidence: String): JsonObject {
        val ends = row(root).getValue("unitEnds").jsonObject
        val last = CuratedWritingRequirementUnits.compile("C1", source).last().id
        val split = withEnds(root, JsonObject(ends + mapOf(
            "C1.U9" to end("PRESENT", "O1"), last to end(secondStatus, *evidence),
        )))
        return if (secondStatus == "CONFLICT") withRelations(split, "CONFLICT") else split
    }

    private fun correction(segmentId: String, original: String, replacement: String, explanation: String) =
        buildJsonObject {
            put("requirementId", "C1"); put("segmentId", segmentId)
            put("patch", buildJsonObject { put("original", original); put("replacement", replacement) })
            put("issueType", "TASK_FACT_CONFLICT"); put("sourceFactId", "T1")
            put("sourceAuthorityId", ""); put("formReason", "NONE"); put("learningText", explanation)
        }

    private fun assemble(draft: JsonObject, case: Case) = CuratedWritingFeedbackV12Policy.assembleRepair(
        draft, case.context, case.requirements,
    )

    private fun validate(draft: JsonObject, case: Case, status: String): JsonObject {
        val independent = buildJsonObject {
            put("statuses", buildJsonObject { put("C1", status) })
            put("evidence", buildJsonObject {
                put("C1", if (status == "MET") buildJsonArray { add(case.segments.first().text) } else buildJsonArray {})
            })
        }
        CuratedWritingFeedbackV12Policy.validateIndependent(independent, case.answer, case.requirements)
        return CuratedWritingFeedbackV12Policy.validateFinal(
            assemble(draft, case), independent, case.context, case.requirements, case.segments,
        )
    }

    private fun JsonArray.strings() = map { it.jsonPrimitive.content }
}
