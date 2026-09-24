package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings

suspend fun Application.configurePersistence() {
    val settings = dependencies.resolve<DatabaseSettings>()
    if (!settings.enabled) {
        environment.log.info("Database persistence is disabled; pool and migrations were not started.")
        return
    }

    dependencies {
        // Ktor DI owns the AutoCloseable. No second ApplicationStopped close handler is needed.
        provide<DatabaseFactory> { DatabaseFactory(settings) }
    }
    // Eager resolution is intentional: never serve requests with a failed/pending migration.
    // JDBC bootstrap runs once at startup, not inside an HTTP handler or an application transaction.
    val factory = dependencies.resolve<DatabaseFactory>()

    environment.log.info(
        "Database persistence initialized. catalog={} mode={} schemaVersion={} migrationsExecuted={}",
        settings.expectedCatalog,
        factory.migrationReport.mode,
        factory.migrationReport.schemaVersion,
        factory.migrationReport.migrationsExecuted,
    )
}
