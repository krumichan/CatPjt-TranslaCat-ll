package jp.co.translacat.languagelearning.shared.persistence

import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.net.URI
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.DriverManager
import java.sql.SQLException
import java.util.*
import kotlin.test.*

/** Runs ONLY via databaseIntegrationTest, never as part of test/check. No Docker required. */
class DatabaseMigrationIntegrationTest {
    @Test
    fun `fresh database has all source columns exact seeds and no learners`() = withScratchDatabase { db ->
        DatabaseFactory(db.settings()).use { factory ->
            assertEquals(4, factory.migrationReport.migrationsExecuted)
            assertEquals(4, factory.migrationReport.schemaVersion.toInt())
            db.connect().use { connection ->
                assertEquals(7L, countTables(connection))
                assertEquals(0L, count(connection, "language_learning_learner"))
                assertEquals(0L, count(connection, "language_learning_user_setting"))
                assertEquals(1L, count(connection, "language_learning_admin_setting"))
                assertEquals(1L, count(connection, "language_learning_listening_policy_setting"))
                assertSourceColumnsAndSeeds(connection, db.name)
            }
        }
    }

    @Test
    fun `second startup does not reset administrator changes or rerun seed`() = withScratchDatabase { db ->
        DatabaseFactory(db.settings()).use { }
        db.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    UPDATE language_learning_admin_setting
                    SET default_daily_sentence_count = 6, updated_by = 'LOCAL_ADMIN'
                    WHERE id = 'DEFAULT'
                """.trimIndent()
                )
            }
        }
        DatabaseFactory(db.settings()).use { second ->
            assertEquals(0, second.migrationReport.migrationsExecuted)
            second.close() // repeated close must be safe (also closed by use)
        }
        DatabaseFactory(db.settings().copy(migrationMode = MigrationMode.VALIDATE)).use { validated ->
            assertEquals(0, validated.migrationReport.migrationsExecuted)
        }
        db.connect().use { connection ->
            assertEquals(
                6L, scalar(
                    connection,
                    "SELECT default_daily_sentence_count FROM language_learning_admin_setting WHERE id='DEFAULT'"
                )
            )
            assertEquals(4L, scalar(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1"))
        }
    }

    @Test
    fun `user settings reference only LL learner and enforce one row per user`() = withScratchDatabase { db ->
        DatabaseFactory(db.settings()).use { }
        db.connect().use { connection ->
            val missingLearner = assertFailsWith<SQLException> { insertUserSetting(connection, 123L) }
            assertTrue(missingLearner.sqlState.orEmpty().startsWith("23"))
            connection.createStatement().use {
                it.executeUpdate(
                    """
                    INSERT INTO language_learning_learner (user_id, created_at, updated_at)
                    VALUES (123, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """.trimIndent()
                )
            }
            insertUserSetting(connection, 123L)
            val duplicate = assertFailsWith<SQLException> { insertUserSetting(connection, 123L) }
            assertTrue(duplicate.sqlState.orEmpty().startsWith("23"))
            val deleteParent = assertFailsWith<SQLException> {
                connection.createStatement()
                    .use { it.executeUpdate("DELETE FROM language_learning_learner WHERE user_id=123") }
            }
            assertTrue(deleteParent.sqlState.orEmpty().startsWith("23"))
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM language_learning_user_setting WHERE user_id=123").use { row ->
                    assertTrue(row.next())
                    assertNull(row.getString("origin_language"))
                    assertNull(row.getString("learning_language"))
                    assertNull(row.getDate("pending_effective_date"))
                    assertEquals("[\"DICTATION\"]", row.getString("default_listening_task_types"))
                }
            }
            connection.metaData.getImportedKeys(db.name, null, "language_learning_user_setting").use { keys ->
                assertTrue(keys.next())
                assertEquals(db.name, keys.getString("PKTABLE_CAT"))
                assertEquals("language_learning_learner", keys.getString("PKTABLE_NAME"))
                assertEquals("user_id", keys.getString("PKCOLUMN_NAME"))
                assertTrue(!keys.next())
            }
        }
    }

    @Test
    fun `unversioned nonempty DB is refused without baselining or deleting data`() = withScratchDatabase { db ->
        db.connect().use { connection ->
            connection.createStatement().use {
                it.executeUpdate("CREATE TABLE preserved_marker (id INT PRIMARY KEY)")
                it.executeUpdate("INSERT INTO preserved_marker VALUES (42)")
            }
        }
        assertFails { DatabaseFactory(db.settings()).use { } }
        db.connect().use { connection ->
            assertEquals(42L, scalar(connection, "SELECT id FROM preserved_marker"))
            assertEquals(
                0L, scalar(
                    connection,
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='language_learning_admin_setting'"
                )
            )
        }
    }

    @Test
    fun `validate mode rejects an empty DB without applying pending migrations`() = withScratchDatabase { db ->
        assertFails { DatabaseFactory(db.settings().copy(migrationMode = MigrationMode.VALIDATE)).use { } }
        db.connect().use { assertEquals(0L, countTables(it)) }
    }

    @Test
    fun `selected catalog mismatch is rejected before any migration DDL`() = withScratchDatabase { db ->
        val other = db.settings().copy(
            jdbcUrl = db.url.replace(db.name, "translacat_ll"),
            expectedCatalog = "translacat_ll",
        )
        val dataSource = object : javax.sql.DataSource {
            override fun getConnection(): Connection = db.connect()
            override fun getConnection(username: String?, password: String?): Connection = db.connect()
            override fun getLogWriter(): java.io.PrintWriter? = null
            override fun setLogWriter(out: java.io.PrintWriter?) = Unit
            override fun setLoginTimeout(seconds: Int) = Unit
            override fun getLoginTimeout(): Int = 0
            override fun getParentLogger(): java.util.logging.Logger = java.util.logging.Logger.getGlobal()
            override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLException("Not a wrapper")
            override fun isWrapperFor(iface: Class<*>?): Boolean = false
        }
        assertFailsWith<IllegalStateException> { DatabaseMigrator.run(dataSource, other) }
        db.connect().use { assertEquals(0L, countTables(it)) }
    }

    @Test
    fun `checksum mismatch stops startup without automatic repair`() = withScratchDatabase { db ->
        DatabaseFactory(db.settings()).use { }
        db.connect().use { connection ->
            connection.createStatement().use {
                it.executeUpdate("UPDATE flyway_schema_history SET checksum=0 WHERE script='V001__create_learner_and_settings.sql'")
            }
        }
        assertFails { DatabaseFactory(db.settings()).use { } }
        db.connect().use { connection ->
            assertEquals(
                0L, scalar(
                    connection,
                    "SELECT checksum FROM flyway_schema_history WHERE script='V001__create_learner_and_settings.sql'"
                )
            )
            assertEquals(1L, count(connection, "language_learning_admin_setting"))
        }
    }

    private fun assertSourceColumnsAndSeeds(connection: Connection, catalog: String) {
        val json = checkNotNull(javaClass.getResourceAsStream("/db/be-settings-baseline.json")).bufferedReader()
            .use { Json.parseToJsonElement(it.readText()).jsonObject }
        json.forEach { (table, entry) ->
            val columns = mutableMapOf<String, Pair<Boolean, Int>>()
            connection.metaData.getColumns(catalog, null, table, null).use { metadata ->
                while (metadata.next()) {
                    columns[metadata.getString("COLUMN_NAME")] =
                        (metadata.getInt("NULLABLE") == DatabaseMetaData.columnNullable) to metadata.getInt("COLUMN_SIZE")
                }
            }
            entry.jsonObject.getValue("columns").jsonArray.forEach { element ->
                val field = element.jsonObject
                val name = field.getValue("column").jsonPrimitive.content
                val actual = checkNotNull(columns[name]) { "Missing $table.$name" }
                assertEquals(field.getValue("nullable").jsonPrimitive.boolean, actual.first, "$table.$name nullability")
                val type = field.getValue("type").jsonPrimitive.content
                if (type.startsWith("VARCHAR(")) {
                    assertEquals(
                        type.substringAfter('(').substringBefore(')').toInt(), actual.second, "$table.$name length"
                    )
                }
            }
            listOf("created_by", "created_at", "updated_by", "updated_at").forEach { assertTrue(it in columns) }
            val seed = entry.jsonObject.getValue("seed").jsonObject
            if (seed.isNotEmpty()) {
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT * FROM $table WHERE id='DEFAULT'").use { row ->
                        assertTrue(row.next())
                        seed.forEach { (column, value) ->
                            if (value is JsonNull) {
                                assertNull(row.getObject(column))
                            } else {
                                val primitive = value.jsonPrimitive
                                when {
                                    primitive.isString -> assertEquals(
                                        primitive.content, row.getString(column), "$table.$column"
                                    )

                                    primitive.content in setOf("true", "false") -> assertEquals(
                                        primitive.boolean, row.getBoolean(column), "$table.$column"
                                    )

                                    else -> assertEquals(
                                        0,
                                        BigDecimal(primitive.content).compareTo(BigDecimal(row.getString(column))),
                                        "$table.$column"
                                    )
                                }
                            }
                        }
                        assertEquals("SYSTEM", row.getString("created_by"))
                        assertTrue(!row.next())
                    }
                }
            }
        }
    }

    private fun insertUserSetting(connection: Connection, userId: Long) {
        connection.prepareStatement(
            """
            INSERT INTO language_learning_user_setting (
                user_id, timezone, daily_sentence_count, daily_speaking_goal_minutes,
                daily_listening_goal_count, default_listening_task_types,
                speaking_voice_id, speaking_playback_speed, created_at, updated_at
            ) VALUES (?, 'Asia/Tokyo', 5, 5, 5, '["DICTATION"]', 'marin', 'NORMAL', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
        """.trimIndent()
        ).use {
            it.setLong(1, userId)
            it.executeUpdate()
        }
    }

    private fun count(connection: Connection, table: String): Long = scalar(connection, "SELECT COUNT(*) FROM $table")
    private fun countTables(connection: Connection): Long = scalar(
        connection, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"
    )

    private fun scalar(connection: Connection, sql: String): Long = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> check(rows.next()); rows.getLong(1) }
    }

    private data class ScratchDatabase(val name: String, val url: String, val username: String, val password: String) {
        fun connect(): Connection = DriverManager.getConnection(url, username, password)
        fun settings() = DatabaseSettings(
            enabled = true, jdbcUrl = url, username = username, password = password,
            expectedCatalog = name, maximumPoolSize = 2, minimumIdle = 0,
        )
    }

    private fun withScratchDatabase(action: (ScratchDatabase) -> Unit) {
        val serverUrl =
            requireNotNull(System.getenv("LL_TEST_MYSQL_URL")) { "Run databaseIntegrationTest with LL_TEST_MYSQL_* variables." }
        val username = requireNotNull(System.getenv("LL_TEST_MYSQL_USERNAME"))
        val password = requireNotNull(System.getenv("LL_TEST_MYSQL_PASSWORD"))
        require(serverUrl.startsWith("jdbc:mysql://")) { "Integration tests require a loopback MySQL URL." }
        val uri = URI(serverUrl.removePrefix("jdbc:"))
        require(uri.host in setOf("localhost", "127.0.0.1", "::1", "[::1]")) {
            "Integration tests may only create databases on loopback MySQL."
        }
        require(uri.path.isNullOrEmpty() || uri.path == "/") {
            "LL_TEST_MYSQL_URL must name the server only, not an existing application database."
        }
        val name = "translacat_ll_it_" + UUID.randomUUID().toString().replace("-", "")
        check(Regex("translacat_ll_it_[0-9a-f]{32}").matches(name))
        val url = serverUrl.substringBefore('?').trimEnd('/') + "/" + name + (uri.rawQuery?.let { "?$it" } ?: "")
        DatabaseTargetGuard.requireMatchingUrl(url, name)
        val db = ScratchDatabase(name, url, username, password)
        DriverManager.getConnection(serverUrl, username, password).use { admin ->
            // No IF NOT EXISTS: a collision must fail before the finally block can delete anything.
            admin.createStatement().use {
                it.executeUpdate("CREATE DATABASE `$name` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
            }
            var failure: Throwable? = null
            try {
                action(db)
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try {
                    admin.createStatement().use { it.executeUpdate("DROP DATABASE `$name`") }
                } catch (cleanupError: Throwable) {
                    if (failure != null) failure.addSuppressed(cleanupError) else throw cleanupError
                }
            }
        }
    }
}
