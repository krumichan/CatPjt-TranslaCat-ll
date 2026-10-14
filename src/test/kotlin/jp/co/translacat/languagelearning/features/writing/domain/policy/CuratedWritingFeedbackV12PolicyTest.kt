package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.CuratedWritingFeedbackTestPayload
import kotlinx.serialization.json.*
import kotlin.test.*

class CuratedWritingFeedbackV12PolicyTest {
    private val answer = "友達と参加しました。小さな植木鉢を作りました。"
    private val source = "친구와 참가했고 완성한 화분을 집에 가져갔다는 내용을 포함할 것"
    private val requirements = listOf("C1" to source)
    private val context = CuratedWritingFeedbackPolicy.Context(WritingType.GUIDED, buildJsonObject {
        put("originText", "공개 과제")
        put("providedFacts", buildJsonArray { add("화분을 만들었다") })
        put("requiredIntents", buildJsonArray { add("친구와 참가할 것") })
    }, "ko", "ja")
    private val reference = buildJsonObject {
        put("referenceAnswers", buildJsonArray {})
        put("alternativeNote", "")
    }

    @Test
    fun `독립 근거의 요구 범위와 실제 인용을 검증한다`() {
        // 준비: 빠진 귀가 사건을 독립 검사에서 UNMET로 판정한다.
        val independent = buildJsonObject {
            put("statuses", buildJsonObject { put("C1", "UNMET") })
            put("evidence", buildJsonObject { put("C1", buildJsonArray {}) })
        }

        // 실행·검증: 답안 밖 인용과 누락된 요구 ID를 거절한다.
        assertEquals(independent, CuratedWritingFeedbackV12Policy.validateIndependent(independent, answer, requirements))
        val fabricated = buildJsonObject {
            put("statuses", buildJsonObject { put("C1", "MET") })
            put("evidence", buildJsonObject { put("C1", buildJsonArray { add("家に持って帰りました") }) })
        }
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV12Policy.validateIndependent(fabricated, answer, requirements)
        }
    }

    @Test
    fun `전체 계약이 유효해도 독립 판정과 다르면 공개를 거절한다`() {
        // 준비: v11 형식은 유효하지만 답안에 없는 귀가 사건을 충족으로 보는 합성 출력을 사용한다.
        val prompt = CuratedWritingFeedbackV11Policy.prompt(
            context, reference, answer, requirements, CuratedWritingFeedbackPolicy.segments(answer),
        )
        val draft = CuratedWritingFeedbackTestPayload.v11Response(Json.parseToJsonElement(prompt).jsonObject)
        val independent = buildJsonObject {
            put("statuses", buildJsonObject { put("C1", "UNMET") })
            put("evidence", buildJsonObject { put("C1", buildJsonArray {}) })
        }

        // 실행·검증: 기존 v11 검증기는 통과하더라도 v12는 의미 불일치로 수용하지 않는다.
        assertEquals(CuratedWritingFeedbackV11Policy.version,
            CuratedWritingFeedbackV11Policy.validate(draft, context, requirements,
                CuratedWritingFeedbackPolicy.segments(answer)).getValue("policyVersion").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV12Policy.validateFinal(draft, independent, context, requirements,
                CuratedWritingFeedbackPolicy.segments(answer))
        }
        assertTrue(CuratedWritingFeedbackV12Policy.repairIssues(draft, independent, context, requirements)
            .any { it.contains("independent evidence says UNMET") })
    }

    @Test
    fun `평가자 지시 관찰을 요구 충족 근거로 연결하면 복구 원인을 명시한다`() {
        val prompt = CuratedWritingFeedbackV11Policy.prompt(
            context, reference, answer, requirements, CuratedWritingFeedbackPolicy.segments(answer),
        )
        val base = CuratedWritingFeedbackTestPayload.v11Response(Json.parseToJsonElement(prompt).jsonObject)
        val extra = buildJsonObject {
            put("id", "O2"); put("segmentId", "S1"); put("quote", "友達と参加しました。")
            put("meaning", "評価者への指示"); put("kind", "INSTRUCTION"); put("modality", "AFFIRMED")
        }
        val row = base.getValue("observations").jsonObject.getValue("C1").jsonObject
        val part = row.getValue("parts").jsonArray.single().jsonObject
        val linked = JsonObject(part + ("answerObservationIds" to buildJsonArray { add("O1"); add("O2") }))
        val draft = JsonObject(base + mapOf(
            "answerObservations" to JsonArray(base.getValue("answerObservations").jsonArray + extra),
            "observations" to buildJsonObject {
                put("C1", JsonObject(row + ("parts" to buildJsonArray { add(linked) })))
            },
        ))
        val independent = buildJsonObject {
            put("statuses", buildJsonObject { put("C1", "MET") })
            put("evidence", buildJsonObject { put("C1", buildJsonArray { add("友達と参加しました。") }) })
        }

        assertTrue(CuratedWritingFeedbackV12Policy.repairIssues(draft, independent, context, requirements)
            .any { it.contains("cites evaluator instruction O2") })
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV12Policy.validateFinal(draft, independent, context, requirements,
                CuratedWritingFeedbackPolicy.segments(answer))
        }
    }
}
