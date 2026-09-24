package jp.co.translacat.languagelearning.shared.persistence

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseSettingsLoaderTest {
    private fun configured() = MapApplicationConfig(
        "database.enabled" to "true",
        "database.jdbcUrl" to "jdbc:mysql://localhost:3306/translacat_ll",
        "database.username" to "test-user",
        "database.password" to "not-a-real-password",
    )

    @Test
    fun `disabled tests need no credentials pool or migration configuration`() {
        val settings = loadDatabaseSettings(MapApplicationConfig("database.enabled" to "false"))
        assertFalse(settings.enabled)
        assertEquals("", settings.jdbcUrl)
    }

    @Test
    fun `disabled mode ignores even invalid irrelevant DB options`() {
        val settings = loadDatabaseSettings(MapApplicationConfig(
            "database.enabled" to "false",
            "database.maximumPoolSize" to "broken",
            "database.migrations.mode" to "broken",
        ))
        assertFalse(settings.enabled)
    }

    @Test
    fun `existing local YAML remains compatible without new optional keys`() {
        val settings = loadDatabaseSettings(configured())
        assertTrue(settings.enabled)
        assertEquals("translacat_ll", settings.expectedCatalog)
        assertEquals(MigrationMode.MIGRATE, settings.migrationMode)
        assertEquals(10, settings.maximumPoolSize)
        assertEquals(2, settings.minimumIdle)
        assertEquals(5_000L, settings.connectionTimeoutMs)
    }

    @Test
    fun `validate mode and explicit pool overrides are loaded`() {
        val config = configured().apply {
            put("database.migrations.mode", "validate")
            put("database.maximumPoolSize", "3")
            put("database.minimumIdle", "0")
            put("database.connectionTimeoutMs", "2000")
        }
        val settings = loadDatabaseSettings(config)
        assertEquals(MigrationMode.VALIDATE, settings.migrationMode)
        assertEquals(3, settings.maximumPoolSize)
        assertEquals(0, settings.minimumIdle)
        assertEquals(2_000L, settings.connectionTimeoutMs)
    }

    @Test
    fun `missing environment file is not interpreted as DB disabled`() {
        val failure = assertFailsWith<IllegalArgumentException> { loadDatabaseSettings(MapApplicationConfig()) }
        assertTrue(failure.message.orEmpty().contains("database.enabled"))
    }

    @Test
    fun `invalid enabled flag is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            loadDatabaseSettings(MapApplicationConfig("database.enabled" to "yes"))
        }
    }

    @Test
    fun `enabled mode requires credentials`() {
        val config = MapApplicationConfig(
            "database.enabled" to "true",
            "database.jdbcUrl" to "jdbc:mysql://localhost:3306/translacat_ll",
            "database.username" to "test-user",
        )
        val failure = assertFailsWith<IllegalArgumentException> { loadDatabaseSettings(config) }
        assertTrue(failure.message.orEmpty().contains("database.password"))
    }

    @Test
    fun `malformed numeric values are not silently defaulted`() {
        val config = configured().apply { put("database.minimumIdle", "two") }
        assertFailsWith<IllegalArgumentException> { loadDatabaseSettings(config) }
    }
}
