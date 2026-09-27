package jp.co.translacat.languagelearning.features.practice

import jp.co.translacat.languagelearning.features.practice.domain.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReadingVerifierContractTest {
    @Test
    fun `합법적인 품질 거부는 parser 실패로 바뀌지 않는다`() {
        // 준비
        val raw = response("supported" to JsonPrimitive(false))

        // 실행
        val verdict = ReadingSemantics.parse(raw, listOf(question()), "COMPREHENSION").single()

        // 검증
        assertEquals(
            "answer is not sufficiently supported",
            ReadingSemantics.rejection(verdict, "A", "COMPREHENSION", "CONTENT", 1),
        )
    }

    @Test
    fun `Reading StrictBool은 문자열과 숫자를 허용하지 않는다`() {
        // 준비
        val values = listOf(JsonPrimitive("true"), JsonPrimitive(1), JsonNull)

        // 실행 및 검증: 원본 Pydantic의 StrictBool 세 필드 계약을 유지한다.
        for (key in listOf("stemPresuppositionsSupported", "distinctReadingTask", "boundedStructureScope")) {
            for (value in values) {
                val error = assertFailsWith<PracticeFailure> {
                    ReadingSemantics.parse(response(key to value), listOf(question()), "STRUCTURE")
                }
                assertEquals("VERIFIER_SCHEMA_INVALID", error.code)
            }
        }
    }

    @Test
    fun `잘못된 지문 근거 연결과 중복 verdict는 프로토콜 실패다`() {
        // 준비
        val cases = listOf(
            response("stemEvidenceSpanIds" to JsonArray(emptyList())),
            response("stemEvidenceSpanIds" to JsonArray(listOf(JsonPrimitive("p2:s1")))),
            response("stemEvidenceSpanIds" to JsonArray(listOf(JsonPrimitive("p1:s1"), JsonPrimitive("p1:s1")))),
            JsonObject(mapOf("verdicts" to JsonArray(List(2) { response().getValue("verdicts").jsonArray.single() }))),
        )

        // 실행 및 검증
        for (raw in cases) {
            val error =
                assertFailsWith<PracticeFailure> { ReadingSemantics.parse(raw, listOf(question()), "COMPREHENSION") }
            assertEquals("VERIFIER_SCHEMA_INVALID", error.code)
        }
    }

    private fun response(vararg changes: Pair<String, JsonElement>): JsonObject {
        val verdict = Json.parseToJsonElement(
            """
            {"order":1,"bestAnswerKey":"A","ambiguous":false,"supported":true,"reason":"합성 판정",
             "modeFit":true,"answerLeakage":false,"contextDependent":true,"distractorsPlausible":true,
             "stemPresuppositionsSupported":true,"distinctReadingTask":true,"readingOperation":"INFERENCE",
             "stemEvidenceSpanIds":["p1:s1"],"boundedStructureScope":true}
        """,
        ).jsonObject
        return buildJsonObject { put("verdicts", JsonArray(listOf(JsonObject(verdict + changes)))) }
    }

    private fun question() = PracticeQuestionContent(
        order = 1, questionType = PracticeQuestionType.SINGLE_CHOICE, difficulty = PracticeDifficulty.CURRENT,
        complexityBand = 3, passageId = "p1", passageText = "Mina brought an umbrella.", prompt = "Synthetic question",
        options = listOf("A", "B", "C", "D").map { PracticeOption(it, "Option $it") }, correctAnswer = listOf("A"),
        skillTag = "CONTENT", explanationOrigin = "합성 해설", explanationLearning = "Synthetic explanation",
    )
}
