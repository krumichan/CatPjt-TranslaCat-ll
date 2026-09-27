package jp.co.translacat.languagelearning.shared.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProviderFailureOriginTest {
    @Test
    fun `기술 표식은 명시한 enum만 허용한다`() {
        // 준비: 표식 없는 구 응답은 호환하고 원문이나 임의 분류는 거부한다.
        val body = Json.parseToJsonElement("""{"failureSignal":"SAFETY"}""").jsonObject

        // 실행 및 검증: 허용된 표식만 전달하며 잘못된 값은 프로토콜 실패로 남긴다.
        assertEquals("SAFETY", providerFailureSignal(body))
        assertEquals(null, providerFailureSignal(null))
        for (value in listOf("\"raw provider message\"", "42", "true", "{}")) {
            assertEquals(
                "MODEL_EXECUTION_PROTOCOL",
                assertFailsWith<ModelExecutionFailure> {
                    providerFailureSignal(Json.parseToJsonElement("""{"failureSignal":$value}""").jsonObject)
                }.code,
            )
        }
    }

    @Test
    fun `기존 응답과 기술 예외 출처를 보존한다`() {
        // 준비: 이전 응답은 출처를 생략하며 새 응답은 SDK와 HTTP 오류를 구분한다.
        val cases = mapOf(
            "{}" to (null to null),
            """{"failureKind":"SDK_TIMEOUT"}""" to ("SDK_TIMEOUT" to null),
            """{"failureKind":"HTTP_STATUS","providerStatus":429}""" to ("HTTP_STATUS" to 429),
        )

        // 실행 및 검증: 업무 계층이 원본 정책을 선택할 수 있도록 정확한 출처를 전달한다.
        cases.forEach { (body, expected) ->
            assertEquals(expected, providerFailureOrigin(Json.parseToJsonElement(body).jsonObject))
        }
    }

    @Test
    fun `모순되거나 잘못된 출처를 프로토콜 오류로 구분한다`() {
        // 준비: 상태만 있는 응답과 알 수 없는 출처를 정상 오류로 보정하지 않는다.
        val invalid = listOf(
            """{"providerStatus":429}""", """{"failureKind":"UNKNOWN"}""",
            """{"failureKind":"HTTP_STATUS"}""",
            """{"failureKind":"TIMEOUT","providerStatus":504}""",
            """{"failureKind":"HTTP_STATUS","providerStatus":"429"}""",
            """{"failureKind":"HTTP_STATUS","providerStatus":200}""",
        )

        // 실행 및 검증: 거부나 일시 장애와 다른 고정 프로토콜 코드로 실패한다.
        invalid.forEach { body ->
            val failure = assertFailsWith<ModelExecutionFailure> {
                providerFailureOrigin(Json.parseToJsonElement(body).jsonObject)
            }
            assertEquals("MODEL_EXECUTION_PROTOCOL", failure.code)
        }
    }
}
