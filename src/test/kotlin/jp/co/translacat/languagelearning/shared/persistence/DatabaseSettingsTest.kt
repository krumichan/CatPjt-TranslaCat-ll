package jp.co.translacat.languagelearning.shared.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DatabaseSettingsTest {
    private fun valid() = DatabaseSettings(
        enabled = true,
        jdbcUrl = "jdbc:mysql://localhost:3306/translacat_ll",
        username = "local-test",
        password = "example-only",
    )

    @Test
    fun `valid default LL connection settings are accepted`() {
        valid().validateForConnection()
        valid().copy(minimumIdle = 0, maximumPoolSize = 1, connectionTimeoutMs = 250)
            .validateForConnection()
        valid().copy(jdbcUrl = "jdbc:mysql://127.0.0.1:3306/translacat_ll?serverTimezone=UTC")
            .validateForConnection()
    }

    @Test
    fun `disabled settings cannot accidentally open a pool`() {
        assertFailsWith<IllegalArgumentException> { valid().copy(enabled = false).validateForConnection() }
    }

    @Test
    fun `Core and system databases are rejected even when expectedCatalog is changed`() {
        listOf("translacat", "mysql", "sys", "information_schema", "performance_schema").forEach { catalog ->
            assertFailsWith<IllegalArgumentException>(catalog) {
                valid().copy(jdbcUrl = "jdbc:mysql://localhost:3306/$catalog", expectedCatalog = catalog)
                    .validateForConnection()
            }
        }
    }

    @Test
    fun `URL and configured catalog must match exactly`() {
        listOf(
            "jdbc:mysql://localhost:3306/translacat",
            "jdbc:mysql://localhost:3306/",
            "jdbc:mysql://localhost:3306/translacat_ll/extra",
            "jdbc:h2:mem:test",
            "jdbc:mysql://localhost:3306/translacat_ll#fragment",
            "jdbc:mysql://user:password@localhost:3306/translacat_ll",
        ).forEach { url ->
            assertFailsWith<IllegalArgumentException> { valid().copy(jdbcUrl = url).validateForConnection() }
        }
    }

    @Test
    fun `driver options may not create or retarget a database or embed credentials`() {
        listOf(
            "createDatabaseIfNotExist=true",
            "sessionVariables=sql_mode=''",
            "user=someone",
            "password=hidden",
            "p%61ssword=hidden",
        ).forEach { query ->
            assertFailsWith<IllegalArgumentException> {
                valid().copy(jdbcUrl = valid().jdbcUrl + "?" + query).validateForConnection()
            }
        }
    }

    @Test
    fun `blank and sentinel credentials are rejected`() {
        listOf("", " ", "not-configured").forEach { username ->
            assertFailsWith<IllegalArgumentException> { valid().copy(username = username).validateForConnection() }
        }
        listOf("", "not-configured").forEach { password ->
            assertFailsWith<IllegalArgumentException> { valid().copy(password = password).validateForConnection() }
        }
    }

    @Test
    fun `pool values are validated before Hikari initialization`() {
        listOf(
            valid().copy(maximumPoolSize = 0),
            valid().copy(minimumIdle = -1),
            valid().copy(maximumPoolSize = 1, minimumIdle = 2),
            valid().copy(connectionTimeoutMs = 249),
        ).forEach { settings ->
            assertFailsWith<IllegalArgumentException> { settings.validateForConnection() }
        }
    }

    @Test
    fun `settings rendering does not include connection credentials or URL`() {
        val settings = valid()
        val rendered = settings.toString()
        assertFalse(rendered.contains(settings.password))
        assertFalse(rendered.contains(settings.username))
        assertFalse(rendered.contains("jdbc:"))
    }

    @Test
    fun `server-selected catalog must match before Flyway is created`() {
        DatabaseTargetGuard.requireSelectedCatalog("translacat_ll", "translacat_ll")
        assertFailsWith<IllegalStateException> {
            DatabaseTargetGuard.requireSelectedCatalog("translacat", "translacat_ll")
        }
        assertFailsWith<IllegalStateException> {
            DatabaseTargetGuard.requireSelectedCatalog(null, "translacat_ll")
        }
    }

    @Test
    fun `migration mode rejects typos instead of disabling migration`() {
        assertEquals(MigrationMode.MIGRATE, MigrationMode.parse("migrate"))
        assertEquals(MigrationMode.VALIDATE, MigrationMode.parse("validate"))
        assertFailsWith<IllegalArgumentException> { MigrationMode.parse("validte") }
        assertFailsWith<IllegalArgumentException> { MigrationMode.parse("disabled") }
    }
}
