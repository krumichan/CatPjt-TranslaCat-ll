package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.plugins.di.dependencies
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings

suspend fun Application.configurePersistence() {
    val settings = dependencies.resolve<DatabaseSettings>()

    if (!settings.enabled) {
        environment.log.info("Database persistence is disabled.")
        return
    }

    check(settings.username != "not-configured") {
        "DB_USERNAME must be configured when database.enalbed=true."
    }

    check(settings.password != "not-configured") {
        "DB_PASSWORD must be configured when database.enalbed=true."
    }

    check(settings.maximumPoolSize > 0) {
        "DB_MAX_POOL_SIZE must be greater than 0."
    }

    check(settings.minimumIdle >= 0) {
        "DB_MIN_IDEL must not exceed DB_MAX_POOL_SIZE."
    }

    val databaseFactory = DatabaseFactory(settings)

    dependencies {
        provide<DatabaseFactory> { databaseFactory }
    }

    monitor.subscribe(ApplicationStopped) {
        databaseFactory.close()
    }

    environment.log.info("Database persistence initialized.")
}