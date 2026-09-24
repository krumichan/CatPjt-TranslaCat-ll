package jp.co.translacat.languagelearning.shared.persistence

import io.ktor.server.application.*
import io.ktor.server.config.*

fun Application.loadDatabaseSettings(): DatabaseSettings = loadDatabaseSettings(environment.config)

/** Kept separate from Application so configuration can be tested without starting a server. */
internal fun loadDatabaseSettings(config: ApplicationConfig): DatabaseSettings {
    val enabled = config.requiredValue("database.enabled").toBooleanStrictOrNull()
        ?: throw IllegalArgumentException("database.enabled must be 'true' or 'false'.")

    // Route/unit tests only need this switch. Do not even parse unrelated DB properties.
    if (!enabled) {
        return DatabaseSettings(enabled = false, jdbcUrl = "", username = "", password = "")
    }

    return DatabaseSettings(
        enabled = true,
        jdbcUrl = config.requiredValue("database.jdbcUrl"),
        username = config.requiredValue("database.username"),
        password = config.requiredValue("database.password"),
        maximumPoolSize = config.optionalInt("database.maximumPoolSize", 10),
        minimumIdle = config.optionalInt("database.minimumIdle", 2),
        connectionTimeoutMs = config.optionalLong("database.connectionTimeoutMs", 5_000),
        expectedCatalog = config.propertyOrNull("database.expectedCatalog")?.getString() ?: "translacat_ll",
        migrationMode = MigrationMode.parse(
            config.propertyOrNull("database.migrations.mode")?.getString() ?: "migrate",
        ),
    ).also { it.validateForConnection() }
}

private fun ApplicationConfig.requiredValue(path: String): String = propertyOrNull(path)?.getString()
    ?: throw IllegalArgumentException("Missing configuration: $path. Load an environment-specific YAML file.")

private fun ApplicationConfig.optionalInt(path: String, default: Int): Int {
    val value = propertyOrNull(path)?.getString() ?: return default
    return value.toIntOrNull() ?: throw IllegalArgumentException("$path must be an integer.")
}

private fun ApplicationConfig.optionalLong(path: String, default: Long): Long {
    val value = propertyOrNull(path)?.getString() ?: return default
    return value.toLongOrNull() ?: throw IllegalArgumentException("$path must be an integer.")
}
