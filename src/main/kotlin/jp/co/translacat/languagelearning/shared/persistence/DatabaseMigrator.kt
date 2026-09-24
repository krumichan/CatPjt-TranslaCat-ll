package jp.co.translacat.languagelearning.shared.persistence

import org.flywaydb.core.Flyway
import javax.sql.DataSource

data class DatabaseMigrationReport(
    val mode: MigrationMode,
    val schemaVersion: String,
    val migrationsExecuted: Int,
)

/** Only versioned SQL in this application's resources may change the LL schema. */
internal object DatabaseMigrator {
    fun run(dataSource: DataSource, settings: DatabaseSettings): DatabaseMigrationReport {
        settings.validateForConnection()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT DATABASE()").use { result ->
                    check(result.next()) { "Unable to determine the selected database." }
                    DatabaseTargetGuard.requireSelectedCatalog(result.getString(1), settings.expectedCatalog)
                }
            }
        }

        val flyway = configuredFlyway(dataSource, settings.expectedCatalog)
        check(flyway.info().all().isNotEmpty()) {
            "No LL migrations were found. Check that db/migration is packaged in the application."
        }
        val executed = when (settings.migrationMode) {
            MigrationMode.MIGRATE -> flyway.migrate().migrationsExecuted
            MigrationMode.VALIDATE -> 0
        }
        // VALIDATE must also reject pending versions; Flyway validate alone is not a deployment gate.
        flyway.validate()
        val info = flyway.info()
        check(info.pending().isEmpty()) {
            "Pending LL migrations remain. Run the approved migration step before using validate mode."
        }
        val current = checkNotNull(info.current()?.version) {
            "The LL schema has no applied version. An empty database is not ready in validate mode."
        }
        return DatabaseMigrationReport(settings.migrationMode, current.toString(), executed)
    }

    internal fun configuredFlyway(dataSource: DataSource, catalog: String): Flyway {
        DatabaseTargetGuard.requireAllowedCatalog(catalog)
        return Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .defaultSchema(catalog)
            .schemas(catalog)
            .createSchemas(false)
            .cleanDisabled(true)
            .baselineOnMigrate(false)
            .validateOnMigrate(true)
            .validateMigrationNaming(true)
            .failOnMissingLocations(true)
            .outOfOrder(false)
            // Keep Flyway's default validation strict so a schema newer than
            // this application is not silently accepted.
            .ignoreMigrationPatterns(*emptyArray<String>())
            .placeholderReplacement(false)
            .connectRetries(0)
            .load()
    }
}
