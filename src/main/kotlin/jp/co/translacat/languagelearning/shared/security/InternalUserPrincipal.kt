package jp.co.translacat.languagelearning.shared.security

internal data class InternalUserPrincipal(val user: VerifiedInternalUser)

internal const val INTERNAL_AUTH = "ll-internal"
