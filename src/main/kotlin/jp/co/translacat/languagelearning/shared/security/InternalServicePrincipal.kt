package jp.co.translacat.languagelearning.shared.security

/** 사용자/관리자 역할이 아니라 BE 서비스의 제한된 Settings 조회 권한이다. */
internal data class InternalServicePrincipal(val service: String)

internal const val SETTINGS_SERVICE_AUTH = "ll-settings-service"
