package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings

fun Application.configureDependencyInjection() {
    val databaseSettings = loadDatabaseSettings()

    dependencies {
        provide<DatabaseSettings> {
            databaseSettings
        }
    }
}
