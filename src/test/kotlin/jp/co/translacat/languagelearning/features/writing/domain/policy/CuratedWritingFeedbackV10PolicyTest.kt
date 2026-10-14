package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.CuratedWritingFeedbackTestPayload
import kotlinx.serialization.json.*
import kotlin.test.*

class CuratedWritingFeedbackV10PolicyTest {
    private val answer = "来週末も出かけたいです。"
    private val requirements = listOf("C1" to "다음 주말에 하고 싶은 일을 표현할 것")
    private val segments = CuratedWritingFeedbackPolicy.segments(answer)
    private val context = context(WritingType.FREE)
    private val reference = buildJsonObject {
        put("referenceAnswers", buildJsonArray { add("秘密の模範解答です。") })
        put("alternativeNote", "참고답안만의 비공개 구체적인 장소")
    }

    @Test
    fun `참고답안의 장소와 표현을 바꿔도 모든 유형의 새 판정 입력은 달라지지 않는다`() {
        // 준비: 공개 조건과 학습자 답안이 같은 상태에서 참고 예시만 변경한다.
        val other = buildJsonObject {
            put("referenceAnswers", buildJsonArray { add("別の場所で別のことをします。") })
            put("alternativeNote", "다른 참고 예시")
        }

        // 실행·검증: 예시가 필수 조건 판단의 입력으로 들어갈 수 없다. 공개 권한은 보존한다.
        WritingType.entries.forEach { type ->
            val ctx = context(type)
            val first = prompt(ctx, reference)
            assertEquals(first, prompt(ctx, other))
            assertFalse("referenceExamples" in first)
            assertFalse(first.toString().contains("秘密"))
            assertEquals(ctx.public, first.getValue("task").jsonObject.getValue("publicPromptAndConditions"))
            assertEquals(CuratedWritingSemanticCriteria.contract(), first.getValue("judgmentCriteria"))
        }
    }

    @Test
    fun `기존 v8 GUIDED 입력과 새 입력은 같은 공개 사실 조건 답안과 source unit을 가진다`() {
        // 준비
        val ctx = context(WritingType.GUIDED)
        val old = Json.parseToJsonElement(CuratedWritingFeedbackPolicy.prompt(ctx, reference, answer, requirements, segments)).jsonObject

        // 실행
        val current = prompt(ctx, reference)

        // 검증: 기존 정책은 고치지 않고 새 정책에서만 참고 예시를 제외한다.
        assertTrue("referenceExamples" in old)
        assertEquals(old.getValue("task"), current.getValue("task"))
        assertEquals(old.getValue("learnerAnswer"), current.getValue("learnerAnswer"))
        assertEquals(CuratedWritingFeedbackPolicy.schema(ctx, requirements, segments),
            CuratedWritingFeedbackV10Policy.schema(ctx, requirements, segments))
    }

    @Test
    fun `합성 충족 판정은 원문과 새 정책 identity를 보존하고 공식 점수를 만들지 않는다`() {
        // 준비: 의미의 실제 정답 여부는 고정 답안 실모델 검증에서 별도로 확인한다.
        val raw = CuratedWritingFeedbackTestPayload.v8Response(prompt(context, reference))
        val original = raw.toString()

        // 실행
        val saved = save(raw)

        // 검증
        assertEquals(original, raw.toString())
        assertEquals("curated-writing-feedback-v10", saved.getValue("policyVersion").jsonPrimitive.content)
        assertEquals("MET", saved.getValue("observations").jsonArray.single().jsonObject.getValue("status").jsonPrimitive.content)
        assertFalse(saved.keys.any { it in setOf("score", "band", "profile", "metrics") })
        assertTrue(saved.getValue("necessaryCorrections").jsonArray.isEmpty())
    }

    @Test
    fun `긴 조건의 문법 꼬리와 별도 출처를 명시해도 누락한 모델 범위를 자동 보정하지 않는다`() {
        // 준비: 실모델이 의미 내용 뒤의 요구 문구를 빠뜨린 형태를 구조 회귀로 고정한다.
        val ctx = context(WritingType.GUIDED)
        val compound = listOf("C1" to "친구와 참가했고 완성한 화분을 집에 가져갔다는 내용을 포함할 것")
        val input = Json.parseToJsonElement(CuratedWritingFeedbackV10Policy.prompt(
            ctx, reference, answer, compound, segments,
        )).jsonObject
        val coverage = input.getValue("responseCoverage").jsonObject
        val range = coverage.getValue("requirementRanges").jsonArray.single().jsonObject

        // 실행·검증: 마지막 세 단위를 의미가 없는 문자라고 버리거나 P 권한을 T로 대체하지 않는다.
        assertEquals("C1.U1", range.getValue("firstUnitId").jsonPrimitive.content)
        assertEquals("C1.U9", range.getValue("lastUnitId").jsonPrimitive.content)
        assertEquals(9, range.getValue("unitCount").jsonPrimitive.int)
        assertEquals(listOf("T1"), coverage.getValue("requiredTaskFactIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("P1"), coverage.getValue("requiredTaskConditionIds").jsonArray.map { it.jsonPrimitive.content })
        val raw = CuratedWritingFeedbackTestPayload.v8Response(input)
        val truncated = Json.parseToJsonElement(raw.toString().replace("C1.U9", "C1.U6"))
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV10Policy.validate(truncated, ctx, compound, segments)
        }
        assertEquals("curated-writing-feedback-v10", CuratedWritingFeedbackV10Policy.validate(
            raw, ctx, compound, segments,
        ).getValue("policyVersion").jsonPrimitive.content)
    }

    @Test
    fun `불확실 부분은 정확히 하나의 근거 설명이 필요하며 누락 중복이나 무관한 설명을 거절한다`() {
        // 준비
        val uncertain = uncertainty()
        val note = buildJsonObject {
            put("requirementId", "C1")
            put("learningText", "二つの文脈上の解釈があり、一方だけが明示された条件を満たします。")
        }

        // 실행·검증: 검증기는 설명의 의미를 보장하지 않지만 근거 자체의 생략은 허용하지 않는다.
        assertFailsWith<IllegalArgumentException> { save(uncertain) }
        val once = JsonObject(uncertain + ("uncertainties" to buildJsonArray { add(note) }))
        assertEquals("UNCERTAIN", save(once).getValue("observations").jsonArray.single().jsonObject.getValue("status").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> {
            save(JsonObject(uncertain + ("uncertainties" to buildJsonArray { add(note); add(note) })))
        }
        val met = CuratedWritingFeedbackTestPayload.v8Response(prompt(context, reference))
        assertFailsWith<IllegalArgumentException> { save(JsonObject(met + ("uncertainties" to buildJsonArray { add(note) }))) }
    }

    @Test
    fun `새 의미 기준도 허위 인용과 임의 점수 및 숨은 조건 필드를 거절한다`() {
        // 준비
        val raw = CuratedWritingFeedbackTestPayload.v8Response(prompt(context, reference))
        val fake = Json.parseToJsonElement(raw.toString().replace(answer, "答えていない文です。"))

        // 실행·검증
        assertFailsWith<IllegalArgumentException> { save(fake) }
        listOf("score", "extraRequirements").forEach { field ->
            assertFailsWith<IllegalArgumentException> { save(JsonObject(raw + (field to JsonPrimitive("injected")))) }
        }
    }

    private fun uncertainty(): JsonObject {
        val raw = CuratedWritingFeedbackTestPayload.v8Response(prompt(context, reference))
        val row = raw.getValue("observations").jsonArray.single().jsonObject
        val part = row.getValue("requirementParts").jsonArray.single().jsonObject
        val relations = row.getValue("relationChecks").jsonObject
        return JsonObject(raw + ("observations" to buildJsonArray { add(JsonObject(row + mapOf(
            "requirementParts" to buildJsonArray { add(JsonObject(part + ("status" to JsonPrimitive("UNCERTAIN")))) },
            "relationChecks" to JsonObject(relations + ("specificity" to JsonPrimitive("UNCERTAIN"))),
        ))) }))
    }

    private fun context(type: WritingType) = CuratedWritingFeedbackPolicy.Context(type, buildJsonObject {
        put("originText", "공개된 문단 과제입니다.")
        put("providedFacts", buildJsonArray { if (type == WritingType.GUIDED) add("화분을 만들었다.") })
        put("requiredIntents", buildJsonArray { if (type == WritingType.GUIDED) add("활동을 설명할 것") })
    }, "ko", "ja")

    private fun prompt(ctx: CuratedWritingFeedbackPolicy.Context, ref: JsonObject) =
        Json.parseToJsonElement(CuratedWritingFeedbackV10Policy.prompt(ctx, ref, answer, requirements, segments)).jsonObject

    private fun save(raw: JsonElement) = CuratedWritingFeedbackV10Policy.validate(raw, context, requirements, segments)
}
