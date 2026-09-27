package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WritingReviewAcceptanceTest {
    private val cases: JsonArray by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream("/contracts/writing-acceptance-python-golden.json"))
            .bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray
    }

    @Test
    fun `Python 기존 판정 함수의 합성 golden 16건과 순서 및 이유가 같다`() {
        assertEquals(16, cases.size)
        for (element in cases) {
            val case = element.jsonObject
            val repaired = case.getValue("repaired").jsonPrimitive.boolean
            val review = WritingReviewParser.parse(case.getValue("review").jsonObject, repaired)
            val decision = WritingReviewAcceptance.decide(
                review = review,
                targetBand = case.getValue("targetBand").jsonPrimitive.int,
                writingType = WritingType.valueOf(case.getValue("writingType").jsonPrimitive.content),
                adjudicated = case.getValue("adjudicated").jsonPrimitive.boolean,
                allowAdjacentRecheck = case.getValue("allowAdjacentRecheck").jsonPrimitive.boolean,
                repaired = repaired,
            )
            assertEquals(
                case.getValue("action").jsonPrimitive.content to case.getValue("reason").jsonPrimitive.content,
                decision.action to decision.reason,
                case.getValue("name").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `보정 보존 PASS와 실패 issue의 모순은 승인 전에 프로토콜 오류가 된다`() {
        val review = cases.first { it.jsonObject.getValue("name").jsonPrimitive.content == "repair-pass" }
            .jsonObject.getValue("review").jsonObject
        val preservation = review.getValue("revisionPreservation").jsonObject
        val invalid = JsonObject(
            review + ("revisionPreservation" to JsonObject(
                preservation + ("issues" to buildJsonArray { add("COSMETIC_ONLY") }),
            )),
        )
        val failure =
            assertFailsWith<WritingReviewProtocolException> { WritingReviewParser.parse(invalid, repaired = true) }
        assertEquals("REVISION_STATUS_ISSUE_MISMATCH", failure.code)
    }

    @Test
    fun `누락 필드와 실패 criterion 불일치는 품질 거부로 둔갑하지 않는다`() {
        val original = cases.first().jsonObject.getValue("review").jsonObject
        val missing = JsonObject(original - "contentHash")
        assertEquals(
            "SCHEMA_FIELDS_INVALID",
            assertFailsWith<WritingReviewProtocolException> { WritingReviewParser.parse(missing) }.code,
        )
        val inconsistent = JsonObject(original + ("issues" to buildJsonArray { add("TASK_TYPE") }))
        assertEquals(
            "CRITERION_ISSUE_MISMATCH",
            assertFailsWith<WritingReviewProtocolException> { WritingReviewParser.parse(inconsistent) }.code,
        )
    }

    @Test
    fun `검증 응답은 실제 수정본의 식별자 해시 세그먼트와 결합한다`() {
        val base = WritingReviewParser.parse(cases.first().jsonObject.getValue("review").jsonObject)
        val draft = WritingDraftEvidence("synthetic origin", emptyList(), emptyList(), emptyList(), "synthetic note")
        fun failure(review: WritingReview) = WritingReviewBinding.failure(
            review, draft, "synthetic-candidate", "a".repeat(64),
        )
        assertEquals(null, failure(base))
        assertEquals("VERIFIER_IDENTITY_MISMATCH", failure(base.copy(candidateId = "other")))
        assertEquals("VERIFIER_CONTENT_HASH_MISMATCH", failure(base.copy(contentHash = "b".repeat(64))))
        assertEquals("VERIFIER_EVIDENCE_SEGMENT_INVALID", failure(base.copy(difficultyEvidenceIds = listOf("F1"))))
        assertEquals("VERIFIER_EVIDENCE_SCOPE_MISMATCH", failure(base.copy(difficultyEvidenceIds = listOf("N1"))))
        assertEquals(
            "VERIFIER_EVIDENCE_SCOPE_MISMATCH",
            failure(
                base.copy(
                    checks = base.checks.map {
                        if (it.criterion == "NOTE_QUALITY") it.copy(
                            evidenceIds = listOf("O1"),
                        ) else it
                    },
                ),
            ),
        )
        assertEquals(
            "VERIFIER_EVIDENCE_SCOPE_MISMATCH",
            failure(
                base.copy(
                    checks = base.checks.map {
                        if (it.criterion == "ANSWER_LEAK") it.copy(
                            evidenceIds = listOf("O1"),
                        ) else it
                    },
                ),
            ),
        )
    }

    @Test
    fun `보정 검증의 production 및 preservation 근거와 revision hash를 검사한다`() {
        val repaired = cases.first { it.jsonObject.getValue("name").jsonPrimitive.content == "repair-pass" }
            .jsonObject.getValue("review").jsonObject
        val base = WritingReviewParser.parse(repaired, repaired = true)
        val draft = WritingDraftEvidence("synthetic origin", emptyList(), emptyList(), emptyList(), "synthetic note")
        fun failure(review: WritingReview) = WritingReviewBinding.failure(
            review, draft, "synthetic-candidate", "a".repeat(64), "b".repeat(64),
        )
        assertEquals(null, failure(base))
        assertEquals(
            "VERIFIER_PRODUCTION_EVIDENCE_INVALID",
            failure(
                base.copy(
                    demands = base.demands?.map { it.copy(evidenceIds = listOf("N1")) },
                ),
            ),
        )
        assertEquals(
            "VERIFIER_REVISION_EVIDENCE_INVALID",
            failure(
                base.copy(
                    preservation = base.preservation?.copy(evidenceIds = listOf("B0", "N1")),
                ),
            ),
        )
        assertEquals("VERIFIER_REVISION_HASH_MISMATCH", failure(base.copy(revisionHash = "c".repeat(64))))
    }

    @Test
    fun `업무 Schema는 Python Pydantic 원본의 합성 golden과 정확히 같다`() {
        val expected =
            checkNotNull(javaClass.getResourceAsStream("/contracts/writing-review-schema-python-golden.json"))
                .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val draft = WritingDraftEvidence(
            "Synthetic origin", listOf("Synthetic fact"), listOf("Synthetic intent"),
            listOf("Synthetic limit"), "Synthetic focus",
        )
        assertEquals(
            expected.getValue("task"),
            WritingReviewSchema.build(
                draft, "synthetic-candidate", "a".repeat(64),
            ),
        )
        assertEquals(
            expected.getValue("repaired"),
            WritingReviewSchema.build(
                draft, "synthetic-candidate", "a".repeat(64), "b".repeat(64),
            ),
        )
    }
}
