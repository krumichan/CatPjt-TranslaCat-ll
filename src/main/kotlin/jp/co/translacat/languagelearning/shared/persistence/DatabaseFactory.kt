package jp.co.translacat.languagelearning.shared.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one pool. Migration completes before repositories can receive the Exposed Database. */
class DatabaseFactory(settings: DatabaseSettings) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val dataSource: HikariDataSource

    val database: Database
    val migrationReport: DatabaseMigrationReport

    init {
        settings.validateForConnection()

        val pool = HikariDataSource(
            HikariConfig().apply {
                poolName = "translacat-language-learning-pool"
                jdbcUrl = settings.jdbcUrl
                driverClassName = settings.driverClassName
                username = settings.username
                password = settings.password
                maximumPoolSize = settings.maximumPoolSize
                minimumIdle = settings.minimumIdle
                connectionTimeout = settings.connectionTimeoutMs
                // Do not change the source service's transaction isolation or session timezone here.
            },
        )

        try {
            migrationReport = DatabaseMigrator.run(pool, settings)
            database = Database.connect(pool)
            dataSource = pool
        } catch (failure: Throwable) {
            // Construction failed before DI could own the object: release the pool ourselves.
            try {
                pool.close()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }

            throw failure
        }
    }

    /** 배포 readiness는 파일/설정 존재가 아니라 현재 DB 연결을 확인한다. */
    fun isReady(): Boolean = try {
        dataSource.connection.use { it.isValid(2) }
    } catch (_: Exception) {
        false
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            dataSource.close()
        }
    }
}
