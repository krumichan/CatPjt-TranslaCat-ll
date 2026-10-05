package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

internal class WritingGenerationPromptGoldenTest {
    private val golden = Json.parseToJsonElement(
        requireNotNull(
            javaClass.getResource(
                "/contracts/writing-generation-python-golden.json",
            ),
        ).readText(),
    ).jsonObject

    @Test
    fun `generation preserves Python baseline with explicit approved retained task comparison additions`() {
        for (case in listOf("baseline", "unconstrainedWindow", "keywordFocused", "translation", "secondAttempt")) {
            val row = if (case == "baseline") golden else golden.getValue(case).jsonObject
            val request = row.getValue("request").jsonObject
            val type = WritingType.valueOf(request.getValue("writingType").jsonPrimitive.content)
            val attempt = if (case == "secondAttempt") 2 else 1
            val feedback = if (attempt == 2) mapOf("SPEC_NOTE_LENGTH" to 1) else emptyMap()
            val actual = WritingGenerationPrompt.build(request, type, 3, attempt, feedback)
            val expected = row.getValue("prompt").jsonPrimitive.content
            // 기존 golden 전체에 승인된 문맥/안내만 더한다. 실제 출력에서 expected를 복사하지 않는다.
            val original = payload(expected).jsonObject
            val retained = original["diversityContext"]?.jsonObject?.get("currentSession")?.jsonArray.orEmpty()
                .filter { it.jsonObject["sourceType"] == JsonPrimitive("WRITING") }.takeLast(4)
            val expectedPayload = if (retained.isEmpty()) original else JsonObject(original + (
                "retainedCurrentWritingTasks" to WritingDiversityPromptContract.historicalRows(retained)
            ))
            val expectedFrame = if (retained.isEmpty()) frame(expected) else frame(expected).replace(
                "<learning-data>", WritingDiversityPromptContract.generation + "\n\n<learning-data>",
            )
            assertEquals(expectedPayload, payload(actual), case)
            assertEquals(expectedFrame, frame(actual), case)
        }
    }

    private fun payload(prompt: String) = Json.parseToJsonElement(
        prompt.substringAfter("<learning-data>\n").substringBefore("\n</learning-data>"),
    )

    private fun frame(prompt: String) = prompt.replace(
        Regex("(?s)(<learning-data>\\n).*?(\\n</learning-data>)"), "$1PAYLOAD$2",
    )
}
