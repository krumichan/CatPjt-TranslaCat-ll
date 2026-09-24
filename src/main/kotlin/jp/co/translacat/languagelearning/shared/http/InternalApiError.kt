package jp.co.translacat.languagelearning.shared.http

import kotlinx.serialization.Serializable

/** BE 어댑터는 code를 기존 외부 오류 코드로 옮긴다. 내부 예외/SQL은 전달하지 않는다. */
@Serializable
internal data class InternalApiError(val code: String, val message: String)
