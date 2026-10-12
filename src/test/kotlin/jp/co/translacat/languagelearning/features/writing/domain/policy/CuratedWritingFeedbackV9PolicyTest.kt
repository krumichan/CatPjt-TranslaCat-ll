package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import kotlin.test.*

class CuratedWritingFeedbackV9PolicyTest {
    private val requirement = "자신의 자료를 고르고 이유를 설명할 것"
    private val requirements = listOf("C1" to requirement)
    private val answer = "本を読みます。まだ理由は決めていません。"
    private val context = CuratedWritingFeedbackPolicy.Context(WritingType.FREE, buildJsonObject {}, "ko", "ja")

    @Test
    fun `서른 요구의 6000 원문 단위는 Provider schema 한도를 넘기지 않는다`() {
        val longRequirements = (1..30).map { index ->
            "C$index" to (1..200).joinToString(" ") { "원문항목$it" }
        }
        val schema = CuratedWritingFeedbackV9Policy.schema(context, longRequirements,
            CuratedWritingFeedbackPolicy.segments(answer))
        val properties = schema.getValue("properties").jsonObject
            .getValue("observations").jsonObject.getValue("properties").jsonObject
        val following = properties.getValue("C30").jsonObject.getValue("properties").jsonObject
            .getValue("followingParts").jsonObject.getValue("items").jsonObject
            .getValue("properties").jsonObject
        fun enumCount(value: JsonElement): Int = when (value) {
            is JsonObject -> ((value["enum"] as? JsonArray)?.size ?: 0) +
                value.filterKeys { it != "enum" }.values.sumOf(::enumCount)
            is JsonArray -> value.sumOf(::enumCount)
            else -> 0
        }
        assertEquals(6000, longRequirements.sumOf { CuratedWritingRequirementUnits.compile(it.first, it.second).size })
        assertEquals(buildJsonObject { put("type", "string") }, following.getValue("startUnitId"))
        assertTrue(enumCount(schema) < 1000)
        assertTrue(schema.toString().toByteArray(Charsets.UTF_8).size < 120000)
    }

    @Test
    fun `고정 C 키와 중간 시작만 요청하고 끝 범위 관계 요약 및 patch를 모델에 요구하지 않는다`() {
        // 준비·실행
        val schema = CuratedWritingFeedbackV9Policy.schema(context, requirements, CuratedWritingFeedbackPolicy.segments(answer))
        val fields = schema.getValue("properties").jsonObject
        val observations = fields.getValue("observations").jsonObject.getValue("properties").jsonObject
        val row = observations.getValue("C1").jsonObject.getValue("properties").jsonObject
        val later = row.getValue("followingParts").jsonObject.getValue("items").jsonObject
            .getValue("properties").jsonObject
        val correction = fields.getValue("necessaryCorrections").jsonObject.getValue("items").jsonObject
            .getValue("properties").jsonObject

        // 검증: 범위의 끝·관계 중복·국소 patch는 서버만 조립한다.
        assertEquals(setOf("C1"), observations.keys)
        assertEquals(setOf("firstPart", "followingParts"), row.keys)
        assertTrue("startUnitId" in later)
        assertFalse("endUnitId" in later)
        assertFalse("relationChecks" in later)
        assertTrue("replacementSegment" in correction)
        assertFalse("patch" in correction)
        assertTrue(fields.getValue("authorityAssignments").jsonObject.getValue("properties").jsonObject.isEmpty())
    }

    @Test
    fun `서버가 마지막 단위와 부분 상태를 복원하고 누락 요소를 실제 요소와 분리한다`() {
        // 준비: 자료 선택은 있지만 개인 이유는 아직 없다.
        val raw = response(first(), following("C1.U4", "ABSENT"))

        // 실행
        val saved = validate(raw)
        val observation = saved.getValue("observations").jsonArray.single().jsonObject
        val parts = observation.getValue("requirementParts").jsonArray.map { it.jsonObject }

        // 검증: 모델의 끝 ID 없이도 요구 원문 전체를 덮고, 없는 이유만 보충 대상으로 남긴다.
        assertEquals("curated-writing-feedback-v9", saved.string("policyVersion"))
        assertEquals("UNMET", observation.string("status"))
        assertEquals(requirement, parts.joinToString("") { it.string("requirementQuote") })
        assertEquals(listOf("PRESENT", "ABSENT"), parts.map { it.string("status") })
        assertTrue(observation.string("originText").contains("이유를 설명할 것"))
        assertTrue(saved.getValue("necessaryCorrections").jsonArray.isEmpty())
    }

    @Test
    fun `동적 P 키는 모두 필요하고 실제 충돌한 같은 부분의 교정만 전달한다`() {
        // 준비: 과제 명시 조건과 학습자의 반대 표현을 구분한다.
        val guided = CuratedWritingFeedbackPolicy.Context(WritingType.GUIDED,
            buildJsonObject { put("requiredIntents", buildJsonArray { add("배식 방식만으로 결과를 단정할 수 없다고 밝힌다.") }) }, "ko", "ja")
        val source = "配食方法だけで説明できます。"
        val req = listOf("C1" to "배식 방식만으로 설명할 수 없다고 밝힌다")
        val assignment = authority("P1", "C1", "C1.U1")
        val raw = response(first("CONFLICT", source, "causality"), assignments = assignment,
            correction = buildJsonObject {
                put("requirementId", "C1"); put("segmentId", "S1")
                put("replacementSegment", "配食方法だけで説明できません。")
                put("authorityId", "P1"); put("formReason", "NONE")
                put("learningText", "この結果だけでは配食方法が唯一の原因だとは断定できません。")
            })

        // 실행·검증: 정상 결과에 P 권한과 서버 계산 patch가 남고, P 누락·다른 위치·개인 교정은 거절한다.
        val saved = CuratedWritingFeedbackV9Policy.validate(raw, guided, req, CuratedWritingFeedbackPolicy.segments(source))
        val correction = saved.getValue("necessaryCorrections").jsonArray.single().jsonObject
        assertEquals("TASK_CONDITION_CONFLICT", correction.string("issueType"))
        assertEquals("P1", correction.string("sourceAuthorityId"))
        assertEquals("配食方法だけで説明できません。", correction.string("replacement"))
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV9Policy.validate(JsonObject(raw + ("authorityAssignments" to buildJsonObject {})),
                guided, req, CuratedWritingFeedbackPolicy.segments(source))
        }
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV9Policy.validate(JsonObject(raw + ("authorityAssignments" to authority("P1", "C1", "C1.U99"))),
                guided, req, CuratedWritingFeedbackPolicy.segments(source))
        }
    }

    @Test
    fun `완성 문장의 차이를 한 patch로 조립하고 잘못된 인용이나 중복 범위를 거절한다`() {
        // 준비: 문장 일부만 바꿨을 때 중복 조사가 생기지 않도록 완성 문장을 받는다.
        val source = "読みやすいと感じます。"
        val req = listOf("C1" to "느낌을 설명한다")
        val alternative = buildJsonObject {
            put("requirementId", "C1"); put("suggestionKind", "EXPRESSION_ONLY")
            put("segmentId", "S1"); put("replacementSegment", "読みやすいと思います。")
            put("learningText", "感想を述べる表現として、こちらの言い方も使えます。")
        }
        val raw = response(first(quote = source), alternative = alternative)

        // 실행·검증: 실제 완성 문장을 저장하고 인용 불일치·겹치는 원문 수정은 실패시킨다.
        val saved = CuratedWritingFeedbackV9Policy.validate(raw, context, req, CuratedWritingFeedbackPolicy.segments(source))
        assertEquals("読みやすいと思います。", saved.getValue("optionalAlternatives").jsonArray.single().jsonObject.string("replacement"))
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV9Policy.validate(response(first(quote = "存在しない表現"), alternative = alternative),
                context, req, CuratedWritingFeedbackPolicy.segments(source))
        }
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV9Policy.validate(JsonObject(raw + ("optionalAlternatives" to JsonArray(listOf(alternative, alternative)))),
                context, req, CuratedWritingFeedbackPolicy.segments(source))
        }
    }

    private fun first(status: String = "PRESENT", quote: String = "本を読みます。", relationKind: String = "NONE") = buildJsonObject {
        put("status", status)
        put("evidence", buildJsonArray { if (status != "ABSENT") add(buildJsonObject {
            put("segmentId", "S1"); put("quote", quote)
        }) })
        put("relationKind", relationKind)
    }

    private fun following(start: String, status: String) = buildJsonObject {
        put("startUnitId", start)
        put("status", status)
        put("evidence", buildJsonArray {})
        put("relationKind", "NONE")
    }

    private fun authority(id: String, requirementId: String, unitId: String) = buildJsonObject {
        put(id, buildJsonObject { put("requirementId", requirementId); put("sourceUnitId", unitId) })
    }

    private fun response(
        first: JsonObject,
        following: JsonObject? = null,
        assignments: JsonObject = buildJsonObject {},
        correction: JsonObject? = null,
        alternative: JsonObject? = null,
    ) = buildJsonObject {
        put("observations", buildJsonObject { put("C1", buildJsonObject {
            put("firstPart", first)
            put("followingParts", buildJsonArray { following?.let { add(it) } })
        }) })
        put("authorityAssignments", assignments)
        put("necessaryCorrections", buildJsonArray { correction?.let { add(it) } })
        put("optionalAlternatives", buildJsonArray { alternative?.let { add(it) } })
        put("uncertainties", buildJsonArray {})
    }

    private fun validate(raw: JsonObject) = CuratedWritingFeedbackV9Policy.validate(raw, context, requirements,
        CuratedWritingFeedbackPolicy.segments(answer))

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
