package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingCandidatePolicy
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingSourceRecovery
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionFailure
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionPort
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WritingReviewExecutionRetryTest {
    private val golden = Json.parseToJsonElement(
        requireNotNull(
            javaClass.getResource(
                "/contracts/writing-source-recovery-python-golden.json",
            ),
        ).readText(),
    ).jsonObject

    @Test
    fun `일반 복구본의 프로토콜 실패는 같은 후보로 한 번만 재검증한다`() = runBlocking {
        // 준비: 첫 응답의 source 상태·문제 목록만 모순되게 한다.
        val valid = golden.getValue("reviewOutput").jsonObject
        val invalid = JsonObject(
            valid + ("sourcePreservation" to buildJsonObject {
                put("status", "PASS")
                put("issues", JsonArray(listOf(JsonPrimitive("MEANING_CHANGED"))))
                put("evidenceSegmentIds", JsonArray(listOf(JsonPrimitive("S0"), JsonPrimitive("O1"))))
            }),
        )
        val seen = mutableListOf<String>()
        var calls = 0
        val model = ModelExecutionPort { command ->
            seen += command.traceId
            calls++
            ModelExecutionResult(if (calls == 1) invalid else valid, 7, 2, "synthetic", "synthetic")
        }
        val execution = WritingReviewExecution(model)
        val request = golden.getValue("request").jsonObject
        val draft = WritingCandidatePolicy.parse(golden.getValue("localized").jsonObject)
        val evidence = WritingSourceRecovery.Evidence(
            golden.getValue("draft").jsonObject.getValue("originText").jsonPrimitive.content,
            golden.getValue("itemHash").jsonPrimitive.content,
        )

        // 실행: 기존 일반 검증 최대 2회 안에서 두 번째 응답을 검사한다.
        val result = execution.assess(
            request, draft, golden.getValue("reviewId").jsonPrimitive.content,
            Instant.now().plusSeconds(30), sourceRecovery = evidence,
        )

        // 검증: 모순 응답을 임의 보정하지 않았고 두 호출이 같은 결합 ID를 사용했다.
        assertEquals(2, calls)
        assertEquals(
            listOf(
                golden.getValue("reviewId").jsonPrimitive.content,
                golden.getValue("reviewId").jsonPrimitive.content,
            ),
            seen,
        )
        assertEquals("PASS", result.review.sourcePreservation?.status)
    }

    @Test
    fun `합법적 Provider 거부와 재판정 실패는 추가 호출하지 않는다`() = runBlocking {
        // 준비: 출력 대신 명시적 거부와 재시도 가능한 Provider 실패를 각각 반환한다.
        val request = golden.getValue("request").jsonObject
        val draft = WritingCandidatePolicy.parse(golden.getValue("localized").jsonObject)
        var refusalCalls = 0
        val refusal = WritingReviewExecution(
            ModelExecutionPort {
                refusalCalls++
                throw ModelExecutionFailure("REFUSAL", 422, false)
            },
        )
        var adjudicationCalls = 0
        val adjudication = WritingReviewExecution(
            ModelExecutionPort {
                adjudicationCalls++
                throw ModelExecutionFailure("PROVIDER_TIMEOUT", 503, true)
            },
        )

        // 실행: 일반 거부와 독립 재판정 실패를 각각 호출한다.
        assertFailsWith<ModelExecutionFailure> {
            refusal.assess(request, draft, "synthetic-refusal", Instant.now().plusSeconds(30))
        }
        assertFailsWith<ModelExecutionFailure> {
            adjudication.assess(
                request, draft, "synthetic-adjudication",
                Instant.now().plusSeconds(30), adjudicator = true,
            )
        }

        // 검증: 두 실패 모두 기존 정책상 한 번의 모델 호출로 종료된다.
        assertEquals(1, refusalCalls)
        assertEquals(1, adjudicationCalls)
    }
}
