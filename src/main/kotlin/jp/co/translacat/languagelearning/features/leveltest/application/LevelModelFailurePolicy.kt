package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.shared.ai.ModelExecutionFailure

/** 범용 transport의 기술 분류를 기존 Python 업무의 예외·status 기준으로 판정한다. */
internal object LevelModelFailurePolicy {
    private val providerCodes = setOf(
        "REFUSAL", "OUTPUT_TOKEN_LIMIT", "RESPONSE_INCOMPLETE", "EMPTY_OUTPUT", "JSON_INVALID",
        "PROVIDER_TIMEOUT", "PROVIDER_UNAVAILABLE", "PROVIDER_CONFIGURATION_ERROR", "PROVIDER_EXECUTION_FAILED",
        "EXECUTION_SCHEMA_INVALID",
    )

    fun originalProvider(failure: ModelExecutionFailure) = failure.code in providerCodes
    fun timeout(failure: ModelExecutionFailure) = failure.code == "MODEL_DEADLINE_EXCEEDED" ||
        failure.failureKind == "TIMEOUT" || (failure.code == "PROVIDER_TIMEOUT" && failure.failureKind == null)

    fun retryGeneration(failure: ModelExecutionFailure): Boolean {
        // 기존 Level Test는 retryable 플래그 대신 원본 status를 검사했다. SDK 연결/timeout에는 status가 없었다.
        if (timeout(failure)) return true
        if (failure.failureKind in setOf("SDK_TIMEOUT", "SDK_CONNECTION", "CONNECTION")) return false
        if (failure.failureKind == "HTTP_STATUS") return failure.providerStatus in setOf(429, 500, 502, 503, 504)
        if (failure.code in setOf(
                "REFUSAL", "OUTPUT_TOKEN_LIMIT", "RESPONSE_INCOMPLETE", "EMPTY_OUTPUT", "JSON_INVALID",
            )
        ) return true
        return failure.failureKind == null && failure.code == "PROVIDER_UNAVAILABLE" && failure.retryable
    }

    fun status(failure: ModelExecutionFailure): Int = when {
        timeout(failure) -> 504
        originalProvider(failure) -> 502
        else -> failure.status
    }
}
