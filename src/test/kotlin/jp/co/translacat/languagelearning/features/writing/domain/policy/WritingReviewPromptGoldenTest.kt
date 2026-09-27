package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class WritingReviewPromptGoldenTest {
    private val cases = Json.parseToJsonElement(
        requireNotNull(
            javaClass.getResource(
                "/contracts/writing-review-prompt-python-golden.json",
            ),
        ).readText(),
    ).jsonArray

    @Test
    fun `ordinary reviewer sees current Python rules and exactly bound task context`() {
        for (entry in cases) {
            val row = entry.jsonObject
            val request = row.getValue("request").jsonObject
            val draft = WritingCandidatePolicy.parse(row.getValue("draft").jsonObject)
            val name = row.getValue("name").jsonPrimitive.content
            val expectedHash = row.getValue("contentHash").jsonPrimitive.content
            assertEquals(expectedHash, WritingCandidatePolicy.contentHash(request, draft), name)
            assertEquals(row.getValue("instructions").jsonPrimitive.content, WritingReviewPrompt.instructions, name)
            assertEquals(
                row.getValue("adjudicatorInstructions").jsonPrimitive.content,
                WritingReviewPrompt.adjudicatorInstructions, name,
            )
            val expected = row.getValue("prompt").jsonPrimitive.content
            val actual = WritingReviewPrompt.build(request, draft, "synthetic-candidate", expectedHash)
            assertEquals(payload(expected), payload(actual), name)
            assertEquals(frame(expected), frame(actual), name)
        }
    }

    private fun payload(prompt: String) = Json.parseToJsonElement(
        prompt.substringAfter("<writing-review-data>\n").substringBefore("\n</writing-review-data>"),
    )

    private fun frame(prompt: String) = prompt.replace(
        Regex("(?s)(<writing-review-data>\\n).*?(\\n</writing-review-data>)"), "$1PAYLOAD$2",
    )
}
