package jp.co.translacat.languagelearning.features.practice

import jp.co.translacat.languagelearning.features.practice.domain.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReadingCandidateContractTest {
    private val request = ReadingRequest(
        "synthetic", "COMPREHENSION", "ko", "en", 3,
        emptyList(), emptyList(), emptyList(), "2026-09-26",
    )
    private val source = Json.parseToJsonElement(
        checkNotNull(
            javaClass.getResourceAsStream(
                "/contracts/practice-execution-python-golden.json",
            ),
        ).bufferedReader().use { it.readText() },
    ).jsonArray
        .map { it.jsonObject }.first {
            it.getValue("request").jsonObject.let { value ->
                value.getValue("mode").jsonPrimitive.content == "COMPREHENSION" &&
                    value.getValue("complexityBand").jsonPrimitive.int == 3
            }
        }
    private val calls = source.getValue("calls").jsonArray.map { it.jsonObject }
    private val passage = ReadingContentPolicy.passage(request, 1, calls.first().getValue("output"))
    private val candidate = calls[1].getValue("output").jsonObject.getValue("questions").jsonArray.first().jsonObject
    private val slot = PracticePolicy.slots("COMPREHENSION", 3).first()

    @Test
    fun `Pydantic extra forbid와 빈 문자열 길이 제한을 보존한다`() {
        // 준비
        val cases = listOf(
            JsonObject(candidate + ("unknownMetadata" to JsonPrimitive("extra"))),
            JsonObject(candidate + ("prompt" to JsonPrimitive("   "))),
            JsonObject(candidate + ("explanationLearning" to JsonPrimitive("x".repeat(3001)))),
        )

        // 실행 및 검증
        for (raw in cases) {
            assertEquals(
                "AI_SCHEMA_INVALID",
                assertFailsWith<PracticeFailure> {
                    ReadingContentPolicy.candidate(request, 1, slot, passage, raw, emptyList())
                }.code,
            )
        }
    }

    @Test
    fun `원본의 문자열 trim과 중복 질문 피드백을 보존한다`() {
        // 준비
        val raw = JsonObject(candidate + ("prompt" to JsonPrimitive("  Synthetic question?  ")))

        // 실행
        val question = ReadingContentPolicy.candidate(request, 1, slot, passage, raw, emptyList())
        val duplicate = assertFailsWith<PracticeFailure> {
            ReadingContentPolicy.candidate(request, 1, slot, passage, raw, listOf(question))
        }

        // 검증
        assertEquals("Synthetic question?", question.prompt)
        assertEquals("reading candidate repeats a normalized prompt on the same passage", duplicate.policyReason)
        assertTrue(
            ReadingAssets.retryFeedback(checkNotNull(duplicate.policyReason)).startsWith("REPAIR_READING_DUPLICATE:"),
        )
    }
}
