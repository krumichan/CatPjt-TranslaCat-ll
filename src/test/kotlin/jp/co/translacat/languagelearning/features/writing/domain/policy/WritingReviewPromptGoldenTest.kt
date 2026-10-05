package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*
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
    fun `ordinary reviewer preserves baseline with approved semantic comparison and calibration additions`() {
        for (entry in cases) {
            val row = entry.jsonObject
            val request = row.getValue("request").jsonObject
            val draft = WritingCandidatePolicy.parse(row.getValue("draft").jsonObject)
            val name = row.getValue("name").jsonPrimitive.content
            val expectedHash = row.getValue("contentHash").jsonPrimitive.content
            assertEquals(expectedHash, WritingCandidatePolicy.contentHash(request, draft), name)
            assertEquals(WritingDiversityPromptContract.calibratedInstructions(
                row.getValue("instructions").jsonPrimitive.content,
            ), WritingReviewPrompt.instructions, name)
            assertEquals(
                WritingDiversityPromptContract.calibratedInstructions(
                    row.getValue("adjudicatorInstructions").jsonPrimitive.content,
                ),
                WritingReviewPrompt.adjudicatorInstructions, name,
            )
            val expected = row.getValue("prompt").jsonPrimitive.content
            val actual = WritingReviewPrompt.build(request, draft, "synthetic-candidate", expectedHash)
            // 과거 원문은 그대로 두고 R 비교 ID와 알려지지 않은 안내 null만 명시적으로 추가한다.
            val original = payload(expected).jsonObject
            val audit = original.getValue("diversityAudit").jsonObject
            val retained = audit.getValue("retainedCurrentItems").jsonArray
            val expectedPayload = if (retained.isEmpty()) original else JsonObject(original + (
                "diversityAudit" to JsonObject(audit + (
                    "retainedCurrentItems" to WritingDiversityPromptContract.historicalRows(retained)
                ))
            ))
            val expectedFrame = if (retained.isEmpty()) frame(expected) else frame(expected).replace(
                "<writing-review-data>", WritingDiversityPromptContract.review + "\n<writing-review-data>",
            )
            assertEquals(expectedPayload, payload(actual), name)
            assertEquals(expectedFrame, frame(actual), name)
        }
    }

    private fun payload(prompt: String) = Json.parseToJsonElement(
        prompt.substringAfter("<writing-review-data>\n").substringBefore("\n</writing-review-data>"),
    )

    private fun frame(prompt: String) = prompt.replace(
        Regex("(?s)(<writing-review-data>\\n).*?(\\n</writing-review-data>)"), "$1PAYLOAD$2",
    )
}
