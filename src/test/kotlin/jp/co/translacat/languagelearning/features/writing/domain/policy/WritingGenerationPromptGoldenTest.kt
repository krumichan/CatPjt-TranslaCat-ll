package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `generation payload and prompt frame match current Python for modes history and retry`() {
        for (case in listOf("baseline", "unconstrainedWindow", "keywordFocused", "translation", "secondAttempt")) {
            val row = if (case == "baseline") golden else golden.getValue(case).jsonObject
            val request = row.getValue("request").jsonObject
            val type = WritingType.valueOf(request.getValue("writingType").jsonPrimitive.content)
            val attempt = if (case == "secondAttempt") 2 else 1
            val feedback = if (attempt == 2) mapOf("SPEC_NOTE_LENGTH" to 1) else emptyMap()
            val actual = WritingGenerationPrompt.build(request, type, 3, attempt, feedback)
            val expected = row.getValue("prompt").jsonPrimitive.content
            assertEquals(payload(expected), payload(actual), case)
            assertEquals(frame(expected), frame(actual), case)
        }
    }

    private fun payload(prompt: String) = Json.parseToJsonElement(
        prompt.substringAfter("<learning-data>\n").substringBefore("\n</learning-data>"),
    )

    private fun frame(prompt: String) = prompt.replace(
        Regex("(?s)(<learning-data>\\n).*?(\\n</learning-data>)"), "$1PAYLOAD$2",
    )
}
