package jp.co.translacat.languagelearning.shared.ai

import kotlinx.serialization.json.*
import kotlin.test.*

class ProviderRetryAfterTest {
    @Test
    fun `숫자 cooldown을 보존하고 잘못된 프로토콜을 거절한다`() {
        // 준비
        val valid = buildJsonObject { put("retryAfterSeconds", 2) }
        val invalid = listOf(JsonPrimitive("2"), JsonPrimitive(0), JsonPrimitive(86_401), JsonPrimitive(1.5), JsonNull)

        // 실행 및 검증
        assertEquals(2L, providerRetryAfter(valid))
        assertNull(providerRetryAfter(JsonObject(emptyMap())))
        invalid.forEach { value ->
            val failure = assertFailsWith<ModelExecutionFailure> {
                providerRetryAfter(
                    JsonObject(mapOf("retryAfterSeconds" to value)),
                )
            }
            assertEquals("MODEL_EXECUTION_PROTOCOL", failure.code)
            assertFalse(failure.retryable)
        }
    }
}
