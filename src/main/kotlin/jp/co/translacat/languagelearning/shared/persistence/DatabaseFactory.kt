package jp.co.translacat.languagelearning.shared.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database

class DatabaseFactory(
    settings: DatabaseSettings
) : AutoCloseable {

    private val dataSource = HikariDataSource(
        HikariConfig().apply {
            poolName = "translacat-language-learning-pool"

            jdbcUrl = settings.jdbcUrl
            driverClassName = settings.driverClassName
            username = settings.username
            password = settings.password

            maximumPoolSize = settings.maximumPoolSize
            minimumIdle = settings.minimumIdle
            connectionTimeout = settings.connectionTimeoutMs
        },
    )

    val database: Database = Database.connect(dataSource)

    override fun close() {
        dataSource.close()
    }
}