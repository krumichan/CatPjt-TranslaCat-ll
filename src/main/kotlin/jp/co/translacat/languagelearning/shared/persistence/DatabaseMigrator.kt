package jp.co.translacat.languagelearning.shared.persistence

import org.flywaydb.core.Flyway
import javax.sql.DataSource

/** 버전이 있는 SQL migration만 LL 스키마를 변경할 수 있다. */
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
        // VALIDATE에서도 미적용 버전은 거부한다. Flyway validate 호출만으로 기동을 허용하지 않는다.
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
            // 현재 코드보다 앞선 DB 버전도 검사에서 무시하지 않도록 기본 ignore 패턴을 비운다.
            .ignoreMigrationPatterns(*emptyArray<String>())
            .placeholderReplacement(false)
            .connectRetries(0)
            .load()
    }
}
