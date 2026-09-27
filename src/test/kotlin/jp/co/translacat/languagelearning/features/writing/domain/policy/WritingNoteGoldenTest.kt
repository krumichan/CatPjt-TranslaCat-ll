package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WritingNoteGoldenTest {
    private val golden = Json.parseToJsonElement(
        requireNotNull(
            javaClass.getResource(
                "/contracts/writing-note-python-golden.json",
            ),
        ).readText(),
    ).jsonObject

    @Test
    fun `Python 현재 note-only prompt와 provider Schema를 재현한다`() {
        val request = golden.getValue("request").jsonObject
        val draft = WritingCandidatePolicy.parse(golden.getValue("draft").jsonObject)
        val localized = WritingCandidatePolicy.parse(golden.getValue("localized").jsonObject)
        val candidateId = golden.getValue("candidateId").jsonPrimitive.content
        val reviewId = golden.getValue("reviewId").jsonPrimitive.content
        val originalHash = golden.getValue("contentHash").jsonPrimitive.content
        val localizedHash = golden.getValue("localizedContentHash").jsonPrimitive.content
        assertEquals(originalHash, WritingCandidatePolicy.contentHash(request, draft))
        assertEquals(localizedHash, WritingCandidatePolicy.contentHash(request, localized))
        assertEquals(golden.getValue("noteSchema"), WritingNotePolicy.localizationSchema)
        assertEquals(golden.getValue("reviewSchema"), WritingNotePolicy.reviewSchema(localized))
        val expectedNote = golden.getValue("notePrompt").jsonPrimitive.content
        val actualNote = WritingNotePolicy.localizationPrompt(request, draft, candidateId, originalHash)
        assertEquals(payload(expectedNote, "writing-note-data"), payload(actualNote, "writing-note-data"))
        val expectedReview = golden.getValue("reviewPrompt").jsonPrimitive.content
        val actualReview = WritingNotePolicy.reviewPrompt(request, localized, reviewId, localizedHash)
        assertEquals(payload(expectedReview, "writing-review-data"), payload(actualReview, "writing-review-data"))
        assertEquals(frame(expectedReview, "writing-review-data"), frame(actualReview, "writing-review-data"))
    }

    @Test
    fun `note proposal과 별도 review의 결합 및 교차 필드를 검사한다`() {
        val localized = WritingCandidatePolicy.parse(golden.getValue("localized").jsonObject)
        val id = golden.getValue("candidateId").jsonPrimitive.content
        val digest = golden.getValue("contentHash").jsonPrimitive.content
        val note = localized.focusReason
        assertEquals(
            note,
            WritingNotePolicy.proposal(
                buildJsonObject {
                    put("candidateId", id); put("contentHash", digest); put("focusReason", note)
                },
                id, digest,
            ),
        )
        assertEquals(
            "VERIFIER_CONTENT_HASH_MISMATCH",
            assertFailsWith<WritingReviewProtocolException> {
                WritingNotePolicy.proposal(
                    buildJsonObject {
                        put("candidateId", id); put("contentHash", "a".repeat(64)); put("focusReason", note)
                    },
                    id, digest,
                )
            }.code,
        )
        val reviewId = golden.getValue("reviewId").jsonPrimitive.content
        val newHash = golden.getValue("localizedContentHash").jsonPrimitive.content
        val pass = buildJsonObject {
            put("candidateId", reviewId); put("contentHash", newHash); put("confidence", JsonNull)
            put("verdict", "PASS"); put("issues", JsonArray(emptyList()))
            put("evidenceSegmentIds", JsonArray(listOf(JsonPrimitive("O1"), JsonPrimitive("N1"))))
        }
        assertEquals("VERIFIED_NOTE_LOCALIZED", WritingNotePolicy.review(pass, reviewId, newHash, localized))
        assertEquals(
            "NOTE_REVIEW_VERDICT_MISMATCH",
            assertFailsWith<WritingReviewProtocolException> {
                WritingNotePolicy.review(
                    JsonObject(
                        pass + ("issues" to JsonArray(
                            listOf(
                                JsonPrimitive("ANSWER_LEAK"),
                            ),
                        )),
                    ),
                    reviewId, newHash, localized,
                )
            }.code,
        )
    }

    private fun payload(value: String, tag: String) = Json.parseToJsonElement(
        value.substringAfter("<$tag>\n").substringBefore("\n</$tag>"),
    )

    private fun frame(value: String, tag: String) = value.replace(
        Regex("(?s)(<$tag>\\n).*?(\\n</$tag>)"), "$1PAYLOAD$2",
    )
}
