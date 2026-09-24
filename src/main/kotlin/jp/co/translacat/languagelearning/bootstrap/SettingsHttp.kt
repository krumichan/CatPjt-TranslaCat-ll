package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.settings.api.settingsRoutes
import jp.co.translacat.languagelearning.features.settings.application.SettingsOperations
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings

internal suspend fun Application.configureSettingsHttp() {
    val internalApi = loadInternalApiSettings()
    if (!internalApi.enabled) {
        environment.log.info("Settings internal API is disabled; no settings routes are exposed.")
        return
    }
    check(dependencies.resolve<DatabaseSettings>().enabled) {
        "내부 Settings API를 켜려면 DB를 활성화해야 합니다."
    }
    val operations = dependencies.resolve<SettingsOperations>()
    configureInternalAuthentication(internalApi)
    routing { settingsRoutes(operations) }
}
