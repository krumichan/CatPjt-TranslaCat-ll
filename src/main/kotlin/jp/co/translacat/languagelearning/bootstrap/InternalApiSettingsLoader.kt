package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import jp.co.translacat.languagelearning.shared.security.InternalApiSettings

internal fun Application.loadInternalApiSettings(): InternalApiSettings {
    val config = environment.config
    fun value(key: String): String? = config.propertyOrNull("internalApi.$key")?.getString()
    val enabled = value("enabled")?.toBooleanStrict() ?: false
    if (!enabled) return InternalApiSettings()
    return InternalApiSettings(
        enabled = true,
        issuer = value("issuer") ?: "translacat-be",
        audience = value("audience") ?: "translacat-ll",
        callerService = value("callerService") ?: "translacat-be",
        maxTtlSeconds = value("maxTtlSeconds")?.toLong() ?: 120,
        clockSkewSeconds = value("clockSkewSeconds")?.toLong() ?: 5,
        secretBase64 = value("secretBase64") ?: "",
    )
}
