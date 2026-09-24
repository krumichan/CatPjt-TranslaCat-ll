package jp.co.translacat.languagelearning.shared.persistence

/** Application settings. Passwords and JDBC URLs must not appear in toString/log output. */
data class DatabaseSettings(
    val enabled: Boolean,
    val jdbcUrl: String,
    val driverClassName: String = "com.mysql.cj.jdbc.Driver",
    val username: String,
    val password: String,
    val maximumPoolSize: Int = 10,
    val minimumIdle: Int = 2,
    val connectionTimeoutMs: Long = 5_000,
    val expectedCatalog: String = "translacat_ll",
    val migrationMode: MigrationMode = MigrationMode.MIGRATE,
) {
    fun validateForConnection() {
        require(enabled) { "Database persistence is disabled; no connection may be created." }
        DatabaseTargetGuard.requireMatchingUrl(jdbcUrl, expectedCatalog)
        require(username.isNotBlank() && username != "not-configured") {
            "database.username must be configured when database.enabled=true."
        }
        require(password.isNotEmpty() && password != "not-configured") {
            "database.password must be configured when database.enabled=true."
        }
        require(driverClassName == "com.mysql.cj.jdbc.Driver") {
            "Only the MySQL Connector/J driver is supported by this database foundation."
        }
        require(maximumPoolSize > 0) { "database.maximumPoolSize must be greater than 0." }
        require(minimumIdle in 0..maximumPoolSize) {
            "database.minimumIdle must be between 0 and database.maximumPoolSize."
        }
        require(connectionTimeoutMs >= 250) {
            "database.connectionTimeoutMs must be at least 250 milliseconds."
        }
    }

    override fun toString(): String =
        "DatabaseSettings(enabled=$enabled, expectedCatalog=$expectedCatalog, " +
            "migrationMode=$migrationMode, maximumPoolSize=$maximumPoolSize, " +
            "minimumIdle=$minimumIdle, connectionTimeoutMs=$connectionTimeoutMs, credentials=<redacted>)"
}
