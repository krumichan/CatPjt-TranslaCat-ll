package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class WritingDifficultyRepairGoldenTest {
    private val golden = Json.parseToJsonElement(
        requireNotNull(
            javaClass.getResource(
                "/contracts/writing-repair-python-golden.json",
            ),
        ).readText(),
    ).jsonObject

    @Test
    fun `targeted repair retains Python binding schema prompt and unchanged checks`() {
        val request = golden.getValue("request").jsonObject
        val original = WritingCandidatePolicy.parse(golden.getValue("original").jsonObject)
        val revised = WritingCandidatePolicy.parse(golden.getValue("revised").jsonObject)
        val plan = WritingDifficultyRepairPlan(
            original, golden.getValue("baseContentHash").jsonPrimitive.content,
            1, WritingType.FREE,
            listOf(WritingRepairObservation("SCOPE_INTERACTION", listOf("O1"))),
            listOf(WritingRepairObservation("SCOPE_TOO_ROUTINE", listOf("O1"))),
        )
        assertEquals(
            golden.getValue("revisedContentHash").jsonPrimitive.content,
            WritingCandidatePolicy.contentHash(request, revised),
        )
        assertEquals(golden.getValue("revisionHash").jsonPrimitive.content, plan.revisionHash(request, revised))
        assertEquals(golden.getValue("reviewPayload"), plan.reviewPayload(request, revised))
        assertEquals(golden.getValue("repairSchema"), plan.generationSchema(request))
        assertEquals(
            payload(golden.getValue("repairPrompt").jsonPrimitive.content),
            payload(plan.generationPrompt(request)),
        )
        assertEquals(
            frame(golden.getValue("repairPrompt").jsonPrimitive.content),
            frame(plan.generationPrompt(request)),
        )
        val reviewPrompt = WritingReviewPrompt.build(
            request, revised, "synthetic-repaired",
            golden.getValue("revisedContentHash").jsonPrimitive.content, plan,
        )
        val expectedReviewPrompt = golden.getValue("reviewPrompt").jsonPrimitive.content
        assertEquals(reviewPayload(expectedReviewPrompt), reviewPayload(reviewPrompt))
        assertEquals(reviewFrame(expectedReviewPrompt), reviewFrame(reviewPrompt))
        val evidence = WritingDraftEvidence(
            revised.originText, revised.providedFacts,
            revised.requiredIntents, revised.responseConstraints, revised.focusReason,
        )
        assertEquals(
            golden.getValue("reviewSchema"),
            WritingReviewSchema.build(
                evidence, "synthetic-repaired",
                golden.getValue("revisedContentHash").jsonPrimitive.content,
                golden.getValue("revisionHash").jsonPrimitive.content,
            ),
        )
        assertEquals(null, plan.changeReason(request, revised))
        assertEquals(
            golden.getValue("unchangedReason").jsonPrimitive.content,
            plan.changeReason(request, original),
        )
    }

    private fun payload(prompt: String) = Json.parseToJsonElement(
        prompt.substringAfter("<learning-data>\n").substringBefore("\n</learning-data>"),
    )

    private fun frame(prompt: String) = prompt.replace(
        Regex("(?s)(<learning-data>\\n).*?(\\n</learning-data>)"), "$1PAYLOAD$2",
    )

    private fun reviewPayload(prompt: String) = Json.parseToJsonElement(
        prompt.substringAfter("<writing-review-data>\n").substringBefore("\n</writing-review-data>"),
    )

    private fun reviewFrame(prompt: String) = prompt.replace(
        Regex("(?s)(<writing-review-data>\\n).*?(\\n</writing-review-data>)"), "$1PAYLOAD$2",
    )
}
