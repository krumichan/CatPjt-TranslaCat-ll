package jp.co.translacat.languagelearning.shared.security

/** started는 Core의 Writing/Speaking 존재 조회로 만든 서명된 사실이다. FE의 query/header는 사용하지 않는다. */
internal data class KeywordPrincipal(val user: VerifiedInternalUser, val hasStartedLearning: Boolean)

internal const val KEYWORD_AUTH = "ll-keywords"
