package jp.co.translacat.languagelearning.shared.ai

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** SDK 예외의 기술적 출처만 보존하며 재시도 여부는 각 업무 정책이 결정한다. */
internal fun providerFailureOrigin(detail: JsonObject?): Pair<String?, Int?> {
    val kindValue = detail?.get("failureKind")?.takeUnless { it == JsonNull }
    val statusValue = detail?.get("providerStatus")?.takeUnless { it == JsonNull }
    if (kindValue == null && statusValue == null) return null to null

    // 이전 서비스가 구분하던 예외 출처를 임의 추정하지 않고 명시된 형식만 수용한다.
    val kind = (kindValue as? JsonPrimitive)?.takeIf { it.isString }?.content
    val status = (statusValue as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    if (kind !in setOf("SDK_TIMEOUT", "TIMEOUT", "SDK_CONNECTION", "CONNECTION", "HTTP_STATUS", "VALUE_ERROR") ||
        (statusValue != null && (status == null || status !in 400..599)) ||
        (kind == "HTTP_STATUS") != (status != null)
    ) throw ModelExecutionFailure("MODEL_EXECUTION_PROTOCOL", 502, false)

    return kind to status
}

/** Provider의 기술 표식만 수용하며 메시지 원문이나 임의 업무 코드는 거부한다. */
internal fun providerFailureSignal(detail: JsonObject?): String? {
    val value = detail?.get("failureSignal")?.takeUnless { it == JsonNull } ?: return null
    val signal = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (signal !in setOf("RATE_LIMIT", "DEADLINE", "SAFETY")) {
        throw ModelExecutionFailure("MODEL_EXECUTION_PROTOCOL", 502, false)
    }
    return signal
}
