package jp.co.translacat.languagelearning.features.speaking

import jp.co.translacat.languagelearning.features.speaking.application.speakingRetryRequest
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingJobRecord
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingResultKind
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingEvaluationContract
import jp.co.translacat.languagelearning.shared.schema.PydanticSchemaFailure
import kotlinx.serialization.json.*
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpeakingRetryContractTest {
    @Test
    fun `코칭 수동 재시도는 증거 hash 멱등 키를 보존하면서 원본 100자 trace 계약을 만족한다`() {
        // 준비: 원본 Python request schema와 실제 길이의 Core 코칭 키로 이전 실패를 재현한다.
        val original = Json.parseToJsonElement(
            checkNotNull(
                javaClass.getResource(
                    "/contracts/speaking-evaluation-python-golden.json",
                ),
            ).readText(),
        ).jsonArray
            .first { it.jsonObject.getValue("kind").jsonPrimitive.content == "coaching" }.jsonObject
            .getValue("rawRequest").jsonObject
        val hash = "a".repeat(64)
        val key = "speaking-coaching:9223372036854775807:free-session-coaching-v1:$hash"
        val request = JsonObject(
            original + mapOf("idempotencyKey" to JsonPrimitive(key), "sourceSnapshotHash" to JsonPrimitive(hash)),
        )
        val previous = JsonObject(request + ("requestId" to JsonPrimitive("$key:manual:1")))
        assertFailsWith<PydanticSchemaFailure> { SpeakingEvaluationContract.request("coaching", previous) }
        val job = SpeakingJobRecord(
            sessionId = Long.MAX_VALUE, problemIndex = 0, resultKind = SpeakingResultKind.SESSION_COACHING,
            resultPolicyVersion = "free-session-coaching-v1", sourceSnapshotHash = hash, request = request,
            availableAt = LocalDateTime.of(2026, 9, 27, 0, 0),
        )

        // 실행
        val retry = speakingRetryRequest(job, 1)
        val validated = SpeakingEvaluationContract.request("coaching", retry)

        // 검증: trace ID와 수동 시도 번호 이외의 immutable 요청은 바뀌지 않는다.
        assertTrue(validated.getValue("requestId").jsonPrimitive.content.length <= 100)
        assertEquals(JsonPrimitive(1), validated["manualRetryAttempt"])
        assertEquals(
            request.filterKeys { it !in setOf("requestId", "manualRetryAttempt") },
            retry.filterKeys { it !in setOf("requestId", "manualRetryAttempt") },
        )
    }
}
