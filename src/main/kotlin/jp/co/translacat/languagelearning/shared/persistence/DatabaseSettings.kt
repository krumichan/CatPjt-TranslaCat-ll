package jp.co.translacat.languagelearning.shared.persistence

data class DatabaseSettings (
    val enabled: Boolean,
    val jdbcUrl: String,
    val driverClassName: String = "com.mysql.cj.jdbc.Driver",
    val username: String,
    val password: String,
    val maximumPoolSize: Int = 10,
    val minimumIdle: Int = 2,
    val connectionTimeoutMs: Long = 5_000,
)