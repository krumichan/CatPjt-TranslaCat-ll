package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings
import jp.co.translacat.languagelearning.shared.persistence.loadDatabaseSettings

fun Application.configureDependencyInjection() {
    val databaseSettings = loadDatabaseSettings()

    dependencies {
        provide<DatabaseSettings> {
            databaseSettings
        }
    }
}