package jp.co.translacat.languagelearning.bootstrap

import java.net.SocketException
import java.net.SocketTimeoutException
import java.sql.SQLException
import java.sql.SQLInvalidAuthorizationSpecException
import java.sql.SQLTimeoutException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.*

class MigrationMainTest {
    @Test
    fun `migration diagnostics expose only fixed codes exception classes and JDBC metadata`() {
        // 준비
        val sql = SQLException("password=DO_NOT_LEAK", "28000", 1045)
        val wrapped = IllegalStateException("jdbc:mysql://secret.internal", sql)
        val failure = RuntimeException("token=DO_NOT_LEAK", wrapped)

        // 실행
        val lines = migrationFailureDiagnosticLines(failure)

        // 검증
        assertEquals(
            listOf(
                "LL_MIGRATION_FAILED: RuntimeException",
                "LL_MIGRATION_CATEGORY: AUTHENTICATION",
                "LL_MIGRATION_CAUSE_1: IllegalStateException",
                "LL_MIGRATION_CAUSE_2: SQLException",
                "LL_MIGRATION_SQLSTATE_2: 28000",
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
        // 준비
        val failure = RuntimeException("outer", SQLException("secret", "NOT_SAFE", 0))

        // 실행
        val lines = migrationFailureDiagnosticLines(failure)

        // 검증
        assertTrue(lines.contains("LL_MIGRATION_SQLSTATE_1: UNKNOWN"))
        assertTrue(lines.contains("LL_MIGRATION_SQLCODE_1: 0"))
        assertTrue(lines.contains("LL_MIGRATION_CATEGORY: SCHEMA_OR_MIGRATION"))
    }

    @Test
    fun `communications failure reports socket reset without exposing messages`() {
        // 준비: JDBC 통신 오류 아래의 운영체제 원인은 고정 코드만 출력한다.
        val socket = SocketException("Connection reset")
        val sql = SQLException("jdbc:mysql://secret.internal password=DO_NOT_LEAK", "08S01", 0, socket)
        val failure = RuntimeException("outer", sql)

        // 실행
        val lines = migrationFailureDiagnosticLines(failure)

        // 검증
        assertTrue(lines.contains("LL_MIGRATION_CATEGORY: CONNECTION"))
        assertTrue(lines.contains("LL_MIGRATION_SOCKET_REASON_2: RESET"))
        assertTrue(lines.contains("LL_MIGRATION_SQLSTATE_1: 08S01"))
        assertFalse(lines.joinToString().contains("Connection reset"))
        assertFalse(lines.joinToString().contains("DO_NOT_LEAK"))
        assertFalse(lines.joinToString().contains("secret.internal"))
    }

    @Test
    fun `socket diagnostics accept only complete allowlisted messages`() {
        // 준비: 유사한 메시지에 붙은 접속 대상과 비밀번호도 출력하지 않는다.
        val failure = SocketException("Connection reset by peer: jdbc:mysql://secret.internal password=DO_NOT_LEAK")

        // 실행
        val lines = migrationFailureDiagnosticLines(failure)

        // 검증
        assertTrue(lines.contains("LL_MIGRATION_SOCKET_REASON_0: UNKNOWN"))
        assertFalse(lines.joinToString().contains("DO_NOT_LEAK"))
        assertFalse(lines.joinToString().contains("secret.internal"))
    }

    @Test
    fun `socket reason distinguishes transport and local permission failures`() {
        // 준비
        val cases = mapOf(
            "Software caused connection abort" to "ABORTED",
            "Broken pipe" to "BROKEN_PIPE",
            "Operation not permitted" to "PERMISSION_DENIED",
            "Network is unreachable" to "NETWORK_UNREACHABLE",
            "No route to host" to "HOST_UNREACHABLE",
            "Connection refused" to "REFUSED",
            "Connection timed out" to "TIMED_OUT",
        )

        // 실행 및 검증
        cases.forEach { (message, reason) ->
            val lines = migrationFailureDiagnosticLines(SocketException(message))
            assertTrue(lines.contains("LL_MIGRATION_SOCKET_REASON_0: $reason"), reason)
        }
    }

    @Test
    fun `TLS failure takes precedence over enclosing JDBC communication failure`() {
        // 준비
        val tls = SSLHandshakeException("certificate secret.internal DO_NOT_LEAK")
        tls.initCause(SocketException("Connection reset"))
        val failure = SQLException("sensitive JDBC message", "08S01", 0, tls)

        // 실행
        val lines = migrationFailureDiagnosticLines(failure)

        // 검증
        assertTrue(lines.contains("LL_MIGRATION_CATEGORY: TLS"))
        assertFalse(lines.joinToString().contains("DO_NOT_LEAK"))
    }

    @Test
    fun `timeout exception types are separated from general connection failures`() {
        // 준비
        val failures = listOf(
            SQLException("sensitive", "08S01", 0, SocketTimeoutException("DO_NOT_LEAK")),
            SQLTimeoutException("DO_NOT_LEAK"),
        )

        // 실행 및 검증
        failures.forEach { failure ->
            val lines = migrationFailureDiagnosticLines(failure)
            assertTrue(lines.contains("LL_MIGRATION_CATEGORY: TIMEOUT"))
            assertFalse(lines.joinToString().contains("DO_NOT_LEAK"))
        }
    }

    @Test
    fun `root JDBC exception retains metadata and authorization classification`() {
        // 준비
        val failure = SQLInvalidAuthorizationSpecException("DO_NOT_LEAK", "28000", 1045)

        // 실행
        val lines = migrationFailureDiagnosticLines(failure)

        // 검증
        assertTrue(lines.contains("LL_MIGRATION_CATEGORY: AUTHENTICATION"))
        assertTrue(lines.contains("LL_MIGRATION_SQLSTATE_0: 28000"))
        assertTrue(lines.contains("LL_MIGRATION_SQLCODE_0: 1045"))
        assertFalse(lines.joinToString().contains("DO_NOT_LEAK"))
    }

    @Test
    fun `cause cycles are visited once`() {
        // 준비
        val failure = RuntimeException("DO_NOT_LEAK")
        val cause = IllegalStateException("DO_NOT_LEAK")
        failure.initCause(cause)
        cause.initCause(failure)

        // 실행
        val lines = migrationFailureDiagnosticLines(failure)

        // 검증
        assertEquals(
            listOf(
                "LL_MIGRATION_FAILED: RuntimeException",
                "LL_MIGRATION_CATEGORY: UNKNOWN",
                "LL_MIGRATION_CAUSE_1: IllegalStateException",
            ),
            lines,
        )
    }

    @Test
    fun `diagnostics inspect at most eight causes`() {
        // 준비
        var failure: Throwable = SocketException("Connection reset")
        repeat(10) {
            failure = IllegalStateException("DO_NOT_LEAK", failure)
        }

        // 실행
        val lines = migrationFailureDiagnosticLines(failure)

        // 검증: 허용 깊이 밖의 원인과 메시지는 진단에 포함하지 않는다.
        assertEquals(8, lines.count { it.startsWith("LL_MIGRATION_CAUSE_") })
        assertTrue(lines.contains("LL_MIGRATION_CATEGORY: UNKNOWN"))
        assertFalse(lines.any { it.contains("SOCKET_REASON") || it.contains("DO_NOT_LEAK") })
    }
}
