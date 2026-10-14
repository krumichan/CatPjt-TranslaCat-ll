package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.CuratedWritingFeedbackTestPayload
import kotlinx.serialization.json.*
import kotlin.test.*

class CuratedWritingFeedbackV11PolicyTest {
    private val answer = "友達と参加しました。小さな植木鉢を作りました。"
    private val source = "친구와 참가했고 완성한 화분을 집에 가져갔다는 내용을 포함할 것"
    private val requirements = listOf("C1" to source)
    private val segments = CuratedWritingFeedbackPolicy.segments(answer)
    private val context = CuratedWritingFeedbackPolicy.Context(WritingType.GUIDED, buildJsonObject {
        put("originText", "공개 과제")
        put("providedFacts", buildJsonArray { add("화분을 만들었다") })
        put("requiredIntents", buildJsonArray { add("친구와 참가할 것") })
    }, "ko", "ja")
    private val reference = buildJsonObject {
        put("referenceAnswers", buildJsonArray { add("비공개 참고 문장") })
        put("alternativeNote", "비공개 표현 예시")
    }

    @Test
    fun `모든 원문 단위는 schema 필수 키이고 누락해도 서버가 채우지 않는다`() {
        // 준비: 모델 응답이 끝의 문법적 요구를 빠뜨리는 실제 실패 패턴을 재현한다.
        val input = input()
        val schema = CuratedWritingFeedbackV11Policy.schema(context, requirements, segments)
        val expected = CuratedWritingRequirementUnits.compile("C1", source).map { it.id }
        val response = response(input)
        val row = response.getValue("observations").jsonObject.getValue("C1").jsonObject
        val assignments = row.getValue("unitAssignments").jsonObject

        // 실행·검증: 모든 ID가 required이며 마지막 ID 없는 출력을 의미 성공으로 만들지 않는다.
        val unitSchema = schema.getValue("properties").jsonObject.getValue("observations").jsonObject
            .getValue("properties").jsonObject.getValue("C1").jsonObject.getValue("properties").jsonObject
            .getValue("unitAssignments").jsonObject
        assertEquals(expected, unitSchema.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        val ids = schema.getValue("properties").jsonObject.getValue("answerObservations").jsonObject
            .getValue("items").jsonObject.getValue("properties").jsonObject.getValue("id").jsonObject
            .getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertContains(ids, "O1")
        assertFalse("A1" in ids)
        val partIds = unitSchema.getValue("properties").jsonObject.getValue("C1.U1").jsonObject
            .getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertContains(partIds, "C1.S1")
        assertFalse("S1" in partIds || "C1.P1" in partIds)
        assertFailsWith<IllegalArgumentException> {
            validate(withRow(response, JsonObject(row + ("unitAssignments" to JsonObject(assignments - "C1.U9")))))
        }
        assertEquals("curated-writing-feedback-v11", validate(response).getValue("policyVersion").jsonPrimitive.content)
    }

    @Test
    fun `독립 부분의 참석과 귀가 누락을 분리하되 원문 끝까지 저장한다`() {
        // 준비: 답안에는 친구 참석과 화분 제작이 있지만 귀가 사건은 없다.
        val original = response(input())
        val row = original.getValue("observations").jsonObject.getValue("C1").jsonObject
        val existing = row.getValue("parts").jsonArray.single().jsonObject
        val units = CuratedWritingRequirementUnits.compile("C1", source)
        val assignments = JsonObject(units.mapIndexed { index, unit ->
            unit.id to JsonPrimitive(when {
                index < 2 -> "C1.S1"
                index < 4 -> "C1.S2"
                else -> "C1.S3"
            })
        }.toMap())
        val present1 = JsonObject(existing + mapOf(
            "id" to JsonPrimitive("C1.S1"), "taskFactIds" to buildJsonArray {},
        ))
        val present2 = JsonObject(existing + mapOf(
            "id" to JsonPrimitive("C1.S2"), "taskConditionIds" to buildJsonArray {},
        ))
        val missing = JsonObject(existing + mapOf(
            "id" to JsonPrimitive("C1.S3"), "status" to JsonPrimitive("ABSENT"),
            "answerObservationIds" to buildJsonArray {}, "taskFactIds" to buildJsonArray {},
            "taskConditionIds" to buildJsonArray {},
        ))
        val split = withRow(original, JsonObject(row + mapOf(
            "unitAssignments" to assignments,
            "parts" to JsonArray(listOf(present1, present2, missing)),
        )))

        // 실행·검증: 상태를 합치거나 빠진 귀가를 자동 충족 처리하지 않는다.
        val result = validate(split)
        val observation = result.getValue("observations").jsonArray.single().jsonObject
        assertEquals("UNMET", observation.getValue("status").jsonPrimitive.content)
        val parts = observation.getValue("requirementParts").jsonArray.map { it.jsonObject }
        assertEquals(listOf("PRESENT", "PRESENT", "ABSENT"), parts.map { it.getValue("status").jsonPrimitive.content })
        assertEquals(source, parts.joinToString("") { it.getValue("requirementQuote").jsonPrimitive.content })
    }

    @Test
    fun `과제 문장 인용과 평가자 지시는 학습자 사실의 근거가 될 수 없다`() {
        // 준비: 과제의 귀가 표현은 실제 답안에 없고 평가자 명령은 권한이 없다.
        val raw = response(input())
        val observed = raw.getValue("answerObservations").jsonArray.single().jsonObject

        // 실행·검증: 가짜 문장 인용과 명령 연결을 둘 다 거절한다.
        val falseQuote = JsonObject(observed + ("quote" to JsonPrimitive("植木鉢を家に持って帰りました")))
        assertFailsWith<IllegalArgumentException> {
            validate(JsonObject(raw + ("answerObservations" to JsonArray(listOf(falseQuote)))))
        }
        val instruction = JsonObject(observed + ("kind" to JsonPrimitive("INSTRUCTION")))
        assertFailsWith<IllegalArgumentException> {
            validate(JsonObject(raw + ("answerObservations" to JsonArray(listOf(instruction)))))
        }
    }

    @Test
    fun `비연속 할당과 존재하지 않는 부분은 거절하고 과거 v10은 동일 정책으로 읽는다`() {
        // 준비: 모든 원문 ID가 있어도 한 부분이 중간에 끊긴 출력을 만든다.
        val raw = response(input())
        val row = raw.getValue("observations").jsonObject.getValue("C1").jsonObject
        val first = row.getValue("parts").jsonArray.single().jsonObject
        val second = JsonObject(first + ("id" to JsonPrimitive("C1.S2")))
        val units = CuratedWritingRequirementUnits.compile("C1", source)
        val assignment = JsonObject(units.mapIndexed { index, unit ->
            unit.id to JsonPrimitive(if (index in 2..4) "C1.S2" else "C1.S1")
        }.toMap())
        val split = withRow(raw, JsonObject(row + mapOf(
            "unitAssignments" to assignment, "parts" to JsonArray(listOf(first, second)),
        )))

        // 실행·검증: 덮어쓰기/보정 없이 비연속을 거절하고 옛 결과의 정책 identity를 유지한다.
        assertFailsWith<IllegalArgumentException> { validate(split) }
        val unknown = JsonObject(assignment + ("C1.U1" to JsonPrimitive("C1.S99")))
        assertFailsWith<IllegalArgumentException> {
            validate(withRow(raw, JsonObject(row + ("unitAssignments" to unknown))))
        }
        val old = CuratedWritingFeedbackTestPayload.v8Response(input())
        assertEquals("curated-writing-feedback-v10", CuratedWritingFeedbackV10Policy.validate(
            old, context, requirements, segments,
        ).getValue("policyVersion").jsonPrimitive.content)
    }

    private fun input() = Json.parseToJsonElement(CuratedWritingFeedbackV11Policy.prompt(
        context, reference, answer, requirements, segments,
    )).jsonObject

    private fun response(input: JsonObject) = CuratedWritingFeedbackTestPayload.v11Response(input)

    private fun validate(value: JsonElement) = CuratedWritingFeedbackV11Policy.validate(
        value, context, requirements, segments,
    )

    private fun withRow(root: JsonObject, row: JsonObject) = JsonObject(root +
        ("observations" to buildJsonObject { put("C1", row) }))
}
