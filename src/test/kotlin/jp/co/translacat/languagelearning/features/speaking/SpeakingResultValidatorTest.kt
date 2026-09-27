package jp.co.translacat.languagelearning.features.speaking

import jp.co.translacat.languagelearning.features.speaking.application.SpeakingResultValidator
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingJobRecord
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingResultKind
import kotlinx.serialization.json.*
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpeakingResultValidatorTest {
    @Test
    fun `원본 Python의 정상 평가 코칭 응답은 Core 최종 저장 계약을 통과한다`() {
        // 준비: 모델을 고정해 원본 Python 서비스에서 직접 얻은 요청과 최종 응답을 읽는다.
        val cases = Json.parseToJsonElement(
            checkNotNull(
                javaClass.getResource(
                    "/contracts/speaking-evaluation-python-golden.json",
                ),
            ).readText(),
        ).jsonArray
            .map { it.jsonObject }.filter { "response" in it }
        assertTrue(cases.isNotEmpty())

        // 실행 및 검증: 별도로 만든 Kotlin expected가 아니라 원본 최종 결과를 저장 경계에 넣는다.
        cases.forEach { case ->
            val request = case.getValue("request").jsonObject
            val coaching = case.getValue("kind") == JsonPrimitive("coaching")
            val job = SpeakingJobRecord(
                sessionId = 1, problemIndex = 0,
                resultKind = if (coaching) SpeakingResultKind.SESSION_COACHING else SpeakingResultKind.SCORED_EVALUATION,
                resultPolicyVersion = request["resultPolicyVersion"]?.jsonPrimitive?.content
                    ?: "speaking-evaluation-policy-v2",
                sourceSnapshotHash = request["sourceSnapshotHash"]?.jsonPrimitive?.contentOrNull,
                request = request, availableAt = LocalDateTime.parse("2026-09-26T00:00:00"),
            )
            SpeakingResultValidator.validate(job, case.getValue("response").jsonObject)
        }
    }

    @Test
    fun `원본 정상 결과도 제출 세션과 요청 식별자가 다르면 저장하지 않는다`() {
        // 준비
        val case = Json.parseToJsonElement(
            checkNotNull(
                javaClass.getResource(
                    "/contracts/speaking-evaluation-python-golden.json",
                ),
            ).readText(),
        ).jsonArray.first().jsonObject
        val request = case.getValue("request").jsonObject
        val response = case.getValue("response").jsonObject
        val job = SpeakingJobRecord(
            sessionId = 1, problemIndex = 0, resultKind = SpeakingResultKind.SCORED_EVALUATION,
            resultPolicyVersion = "speaking-evaluation-policy-v2", sourceSnapshotHash = null,
            request = request, availableAt = LocalDateTime.parse("2026-09-26T00:00:00"),
        )

        // 실행 및 검증: 응답 내용이 정상이어도 다른 요청·세션에 귀속시키지 않는다.
        for (key in listOf("requestId", "sessionId")) {
            assertFailsWith<IllegalArgumentException> {
                SpeakingResultValidator.validate(job, JsonObject(response + (key to JsonPrimitive("different"))))
            }
        }
    }
}
