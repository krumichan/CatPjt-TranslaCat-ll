package jp.co.translacat.languagelearning.shared.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** typed 기술 응답만 사용한다. 잘못된 cooldown을 임의 보정하거나 즉시 재시도하지 않는다. */
internal fun providerRetryAfter(detail: JsonObject?): Long? {
    // 재시도 시간 필드가 없으면 호출자가 별도 cooldown으로 해석할 값이 없다.
    val value = detail?.get("retryAfterSeconds") ?: return null
    val primitive = value as? JsonPrimitive
    val seconds = primitive?.takeUnless { it.isString }?.longOrNull

    // 문자열, 범위 밖 숫자 등은 제공자 프로토콜 오류로 처리한다.
    if (seconds == null || seconds !in 1..86_400) throw ModelExecutionFailure("MODEL_EXECUTION_PROTOCOL", 502, false)

    return seconds
}
