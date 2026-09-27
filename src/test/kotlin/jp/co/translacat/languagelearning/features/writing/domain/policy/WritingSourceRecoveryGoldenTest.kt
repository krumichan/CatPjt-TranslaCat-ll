package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WritingSourceRecoveryGoldenTest {
    private val golden = Json.parseToJsonElement(
        requireNotNull(
            javaClass.getResource(
                "/contracts/writing-source-recovery-python-golden.json",
            ),
        ).readText(),
    ).jsonObject

    @Test
    fun `Python source 제안 계약의 hash와 schema와 prompt를 재현한다`() {
        // 준비: Python이 내보낸 합성 원본과 source 언어 복구본을 읽는다.
        val request = golden.getValue("request").jsonObject
        val original = WritingCandidatePolicy.parse(golden.getValue("draft").jsonObject)
        val itemId = golden.getValue("itemId").jsonPrimitive.content
        val batchId = golden.getValue("batchId").jsonPrimitive.content

        // 실행: 같은 입력으로 LL 제안 계약과 검증 결합값을 만든다.
        val input = WritingSourceRecovery.input(itemId, original)
        val schema = WritingSourceRecovery.schema(request, 3, listOf(input))
        val prompt = WritingSourceRecovery.prompt(request, batchId, listOf(input))

        // 검증: provider에 전달되는 제한과 내용 결합값이 Python과 같다.
        assertEquals(golden.getValue("itemHash").jsonPrimitive.content, input.contentHash)
        assertEquals(
            golden.getValue("batchHash").jsonPrimitive.content,
            WritingSourceRecovery.batchHash(listOf(input)),
        )
        assertEquals(golden.getValue("sourceSchema"), schema)
        assertEquals(golden.getValue("sourcePrompt").jsonPrimitive.content, prompt)
        val proposals = WritingSourceRecovery.proposals(
            golden.getValue("sourceOutput"), batchId,
            WritingSourceRecovery.batchHash(listOf(input)), listOf(input),
        )
        assertEquals(
            golden.getValue("localized").jsonObject.getValue("originText").jsonPrimitive.content,
            proposals.single().draft.originText,
        )
    }

    @Test
    fun `복구본 검증은 원문과 수정본의 의미 근거를 모두 요구한다`() {
        // 준비: Python의 source 복구 검증 응답과 의미 보존 결합값을 읽는다.
        val request = golden.getValue("request").jsonObject
        val original = WritingCandidatePolicy.parse(golden.getValue("draft").jsonObject)
        val localized = WritingCandidatePolicy.parse(golden.getValue("localized").jsonObject)
        val evidence = WritingSourceRecovery.Evidence(
            original.originText,
            golden.getValue("itemHash").jsonPrimitive.content,
        )
        val reviewId = golden.getValue("reviewId").jsonPrimitive.content
        val contentHash = golden.getValue("contentHash").jsonPrimitive.content
        val recoveryHash = evidence.bindingHash(request, localized)

        // 실행: 복구본 전용 schema, prompt, parser를 통과시킨다.
        val schema = WritingReviewSchema.build(
            WritingDraftEvidence(
                localized.originText,
                localized.providedFacts, localized.requiredIntents, localized.responseConstraints,
                localized.focusReason,
            ),
            reviewId, contentHash, recoveryHash = recoveryHash,
        )
        val prompt = WritingReviewPrompt.build(
            request, localized, reviewId, contentHash,
            sourceRecovery = evidence,
        )
        val parsed = WritingReviewParser.parse(
            golden.getValue("reviewOutput").jsonObject,
            recovered = true,
        )

        // 검증: 독립 검증의 입력과 의미 보존 판정이 Python 계약과 같다.
        assertEquals(golden.getValue("recoveryHash").jsonPrimitive.content, recoveryHash)
        assertEquals(golden.getValue("reviewSchema"), schema)
        val expectedPrompt = golden.getValue("reviewPrompt").jsonPrimitive.content
        assertEquals(payload(expectedPrompt), payload(prompt))
        assertEquals(frame(expectedPrompt), frame(prompt))
        assertEquals("PASS", parsed.sourcePreservation?.status)
        assertEquals("ACCEPT", WritingReviewAcceptance.decide(parsed, 3, WritingType.TRANSLATION).action)
        assertEquals(
            true,
            WritingDiversityValidator(request.getValue("diversityContext").jsonObject)
                .validate(
                    localized.originText, WritingCandidatePolicy.metadata(localized.metadata), emptyList(),
                ).accepted,
        )
        val malformed = JsonObject(
            golden.getValue("reviewOutput").jsonObject +
                ("sourcePreservation" to buildJsonObject {
                    put("status", "PASS")
                    put("issues", JsonArray(listOf(JsonPrimitive("FACT_CHANGED"))))
                    put("evidenceSegmentIds", JsonArray(listOf(JsonPrimitive("S0"), JsonPrimitive("O1"))))
                }),
        )
        assertEquals(
            "SOURCE_STATUS_ISSUE_MISMATCH",
            assertFailsWith<WritingReviewProtocolException> {
                WritingReviewParser.parse(malformed, recovered = true)
            }.code,
        )
    }

    private fun payload(value: String) = Json.parseToJsonElement(
        value.substringAfter("<writing-review-data>\n").substringBefore("\n</writing-review-data>"),
    )

    private fun frame(value: String) = value.replace(
        Regex("(?s)(<writing-review-data>\\n).*?(\\n</writing-review-data>)"), "$1PAYLOAD$2",
    )
}
