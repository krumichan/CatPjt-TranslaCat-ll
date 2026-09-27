package jp.co.translacat.languagelearning.features.writing.application

/** 원본 Python의 생성 후보 거부와 필수 검증 실패를 구분한다. */
internal fun writingGeneratorRejection(code: String): String? = when (code) {
    "REFUSAL" -> "GENERATOR_PROVIDER_REFUSAL"
    "OUTPUT_TOKEN_LIMIT" -> "GENERATOR_OUTPUT_TOKEN_LIMIT"
    "RESPONSE_INCOMPLETE" -> "GENERATOR_RESPONSE_INCOMPLETE"
    "EMPTY_OUTPUT" -> "GENERATOR_EMPTY_RESPONSE"
    "JSON_INVALID" -> "GENERATOR_JSON_INVALID"
    else -> null
}

internal data class WritingPublicFailure(val status: Int, val code: String)

/** 동기 재생성의 실패를 기존 외부 HTTP 계약으로 전달하며 원인 코드는 결과에 따로 보존한다. */
internal fun writingPublicFailure(code: String, stage: String?): WritingPublicFailure = when {
    code in setOf("EXECUTION_SCHEMA_INVALID", "PROVIDER_CONFIGURATION_ERROR") ->
        WritingPublicFailure(502, "WRITING_PROVIDER_CONFIGURATION_ERROR")

    code == "WRITING_GENERATION_DEADLINE_EXCEEDED" -> WritingPublicFailure(504, code)
    code in setOf("WRITING_GENERATION_VALIDATION_EXHAUSTED", "WRITING_SOURCE_LANGUAGE_EXHAUSTED") ->
        WritingPublicFailure(422, code)

    stage == "VERIFICATION" || code == "VERIFIER_SCHEMA_INVALID" ->
        WritingPublicFailure(503, "WRITING_VERIFICATION_UNAVAILABLE")

    code in setOf(
        "PROVIDER_TIMEOUT", "PROVIDER_UNAVAILABLE", "MODEL_EXECUTION_TRANSPORT",
        "MODEL_DEADLINE_EXCEEDED",
    ) -> WritingPublicFailure(503, "WRITING_GENERATION_UNAVAILABLE")

    else -> WritingPublicFailure(502, "WRITING_INTERNAL_ERROR")
}
