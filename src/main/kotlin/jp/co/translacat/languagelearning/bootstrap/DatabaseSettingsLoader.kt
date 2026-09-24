package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.config.*
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings
import jp.co.translacat.languagelearning.shared.persistence.MigrationMode

fun Application.loadDatabaseSettings(): DatabaseSettings = loadDatabaseSettings(environment.config)

/** 서버를 시작하지 않고 설정을 검사할 수 있도록 Application과 설정 변환을 분리한다. */
internal fun loadDatabaseSettings(config: ApplicationConfig): DatabaseSettings {
    val enabled = config.requiredValue("database.enabled").toBooleanStrictOrNull()
        ?: throw IllegalArgumentException("database.enabled must be 'true' or 'false'.")

    // DB를 사용하지 않는 테스트에서는 다른 DB 속성까지 파싱하거나 연결하지 않는다.
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
