package jp.co.translacat.languagelearning.bootstrap

import java.sql.SQLException
import kotlin.test.*

class MigrationMainTest {
    @Test
    fun `migration diagnostics expose only exception classes and JDBC metadata`() {
        val sql = SQLException("password=DO_NOT_LEAK", "08S01", 1045)
        val wrapped = IllegalStateException("jdbc:mysql://secret.internal", sql)
        val failure = RuntimeException("token=DO_NOT_LEAK", wrapped)

        val lines = migrationFailureDiagnosticLines(failure)

        assertEquals(
            listOf(
                "LL_MIGRATION_FAILED: RuntimeException",
                "LL_MIGRATION_CAUSE_1: IllegalStateException",
                "LL_MIGRATION_CAUSE_2: SQLException",
                "LL_MIGRATION_SQLSTATE_2: 08S01",
                "LL_MIGRATION_SQLCODE_2: 1045",
            ),
            lines,
        )
        val rendered = lines.joinToString("\n")
        assertFalse(rendered.contains("DO_NOT_LEAK"))
        assertFalse(rendered.contains("jdbc:mysql"))
    }

    @Test
    fun `non standard SQL state is redacted`() {
        val failure = RuntimeException("outer", SQLException("secret", "NOT_SAFE", 0))

        val lines = migrationFailureDiagnosticLines(failure)

        assertTrue(lines.contains("LL_MIGRATION_SQLSTATE_1: UNKNOWN"))
        assertTrue(lines.contains("LL_MIGRATION_SQLCODE_1: 0"))
    }
}
