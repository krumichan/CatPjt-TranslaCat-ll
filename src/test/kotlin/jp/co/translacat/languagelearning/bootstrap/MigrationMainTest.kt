package jp.co.translacat.languagelearning.bootstrap

import jp.co.translacat.languagelearning.shared.persistence.DatabaseMigrationReport
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings
import jp.co.translacat.languagelearning.shared.persistence.MigrationMode
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

    @Test
    fun `empty command preserves approved migration behavior and completion marker`() {
        // 준비: 기존 명령은 이행 계정과 명시 승인을 그대로 사용한다.
        val environment = mapOf(
            "LL_ALLOW_MIGRATIONS" to "true",
            "DB_JDBC_URL" to "jdbc:mysql://127.0.0.1:3306/translacat_ll?useSSL=false",
            "DB_USERNAME" to "synthetic_migrator",
            "DB_PASSWORD" to "DO_NOT_LEAK",
        )
        val settings = mutableListOf<DatabaseSettings>()
        val output = mutableListOf<String>()

        // 실행
        runDatabaseCommand(emptyArray(), environment::get, { current ->
            settings += current
            DatabaseMigrationReport(MigrationMode.MIGRATE, "041", 2)
        }, output::add)

        // 검증: 기존 migrate marker는 바꾸지 않고 읽기 전용 성공으로 표시하지 않는다.
        assertEquals(1, settings.size)
        assertEquals(MigrationMode.MIGRATE, settings.single().migrationMode)
        assertEquals("synthetic_migrator", settings.single().username)
        assertEquals(listOf("LL_MIGRATION_COMPLETE"), output)
        assertFalse(output.joinToString().contains("DO_NOT_LEAK"))
    }

    @Test
    fun `explicit validation uses runtime settings without migration approval or mode environment override`() {
        // 준비: 환경의 migrate 값과 승인 true도 명시적 validate 명령을 DDL 경로로 바꿀 수 없다.
        val environment = mapOf(
            "LL_ALLOW_MIGRATIONS" to "true",
            "DB_MIGRATION_MODE" to "migrate",
            "DB_JDBC_URL" to "jdbc:mysql://127.0.0.1:3306/translacat_ll?useSSL=false",
            "DB_USERNAME" to "synthetic_runtime",
            "DB_PASSWORD" to "DO_NOT_LEAK",
        )
        val settings = mutableListOf<DatabaseSettings>()
        val output = mutableListOf<String>()

        // 실행: validate는 DDL 승인이 없어도 같은 엄격 검증 모드다.
        listOf(environment, environment - "LL_ALLOW_MIGRATIONS").forEach { currentEnvironment ->
            runDatabaseCommand(arrayOf("--validate-database"), currentEnvironment::get, { current ->
                settings += current
                DatabaseMigrationReport(MigrationMode.VALIDATE, "041", 0)
            }, output::add)
        }

        // 검증: 실제 연결 함수가 받은 모드·계정과 출력은 모두 읽기 전용 계약이다.
        assertEquals(2, settings.size)
        settings.forEach { current ->
            assertEquals(MigrationMode.VALIDATE, current.migrationMode)
            assertEquals("synthetic_runtime", current.username)
            assertEquals("translacat_ll", current.expectedCatalog)
            assertEquals(2, current.maximumPoolSize)
            assertEquals(0, current.minimumIdle)
        }
        assertEquals(List(2) { listOf("LL_DATABASE_VALIDATION_COMPLETE",
            "LL_DATABASE_SCHEMA_VERSION: 041", "LL_DATABASE_MIGRATIONS_EXECUTED: 0") }.flatten(), output)
        assertFalse(output.joinToString().contains("DO_NOT_LEAK"))
    }

    @Test
    fun `migration without exact approval fails before connection settings are read`() {
        // 준비
        listOf<String?>(null, "", "false", "TRUE").forEach { approval ->
            val environmentReads = mutableListOf<String>()
            var connections = 0

            // 실행 및 검증: 승인 실패는 DB 연결과 접속 설정 조회보다 앞선다.
            assertFailsWith<IllegalStateException> {
                runDatabaseCommand(emptyArray(), { name ->
                    environmentReads += name
                    approval
                }, {
                    connections++
                    error("Must not connect")
                })
            }
            assertEquals(listOf("LL_ALLOW_MIGRATIONS"), environmentReads)
            assertEquals(0, connections)
        }
    }

    @Test
    fun `unknown duplicate or additional command arguments fail before any environment read or connection`() {
        // 준비: 명령·승인·환경변수의 혼합으로 알 수 없는 인자를 허용하지 않는다.
        val invalid = listOf(
            arrayOf("--validate"), arrayOf("--validate-databas"), arrayOf("--migrate-database"),
            arrayOf("--validate-database=true"), arrayOf("--validate-database", "--validate-database"),
            arrayOf("--validate-database", "--migrate-database"), arrayOf("--migrate-database", "--validate-database"),
            arrayOf("--validate-database", "--extra"),
            arrayOf("--extra", "--validate-database"), arrayOf(""),
        )

        // 실행 및 검증
        invalid.forEach { arguments ->
            var environmentReads = 0
            var connections = 0
            assertFailsWith<IllegalArgumentException> {
                runDatabaseCommand(arguments, {
                    environmentReads++
                    "true"
                }, {
                    connections++
                    error("Must not connect")
                })
            }
            assertEquals(0, environmentReads)
            assertEquals(0, connections)
        }
    }

    @Test
    fun `validation rejects missing credentials and other catalog before connection`() {
        // 준비: 읽기 전용 명령도 기존 대상·필수 접속 설정 검사를 우회하지 않는다.
        val base = mapOf(
            "DB_JDBC_URL" to "jdbc:mysql://127.0.0.1:3306/translacat_ll?useSSL=false",
            "DB_USERNAME" to "synthetic_runtime", "DB_PASSWORD" to "DO_NOT_LEAK",
        )
        val invalid = listOf(base - "DB_PASSWORD", base + ("DB_JDBC_URL" to "jdbc:mysql://127.0.0.1:3306/translacat"))

        // 실행 및 검증
        invalid.forEach { environment ->
            var connections = 0
            assertFails {
                runDatabaseCommand(arrayOf("--validate-database"), environment::get, {
                    connections++
                    error("Must not connect")
                })
            }
            assertEquals(0, connections)
        }
    }

    @Test
    fun `validation never publishes success for an applied migration wrong mode or unsafe report`() {
        // 준비: 완료 marker는 실제 validate 모드·적용 0건·안전한 버전 메타데이터에만 허용한다.
        val environment = mapOf(
            "DB_JDBC_URL" to "jdbc:mysql://127.0.0.1:3306/translacat_ll?useSSL=false",
            "DB_USERNAME" to "synthetic_runtime", "DB_PASSWORD" to "DO_NOT_LEAK",
        )
        val reports = listOf(
            DatabaseMigrationReport(MigrationMode.MIGRATE, "041", 0),
            DatabaseMigrationReport(MigrationMode.VALIDATE, "041", 1),
            DatabaseMigrationReport(MigrationMode.VALIDATE, "DO_NOT_LEAK", 0),
            DatabaseMigrationReport(MigrationMode.VALIDATE, "0", 0),
        )

        // 실행 및 검증
        reports.forEach { report ->
            val output = mutableListOf<String>()
            assertFailsWith<IllegalStateException> {
                runDatabaseCommand(arrayOf("--validate-database"), environment::get, { report }, output::add)
            }
            assertTrue(output.isEmpty())
        }
    }

    @Test
    fun `strict validation failure propagates without any success output`() {
        // 준비
        val environment = mapOf(
            "DB_JDBC_URL" to "jdbc:mysql://127.0.0.1:3306/translacat_ll?useSSL=false",
            "DB_USERNAME" to "synthetic_runtime", "DB_PASSWORD" to "DO_NOT_LEAK",
        )
        val failure = IllegalStateException("Synthetic future migration rejection")
        val output = mutableListOf<String>()

        // 실행
        val caught = assertFailsWith<IllegalStateException> {
            runDatabaseCommand(arrayOf("--validate-database"), environment::get, { throw failure }, output::add)
        }

        // 검증: 검증 오류를 정상 완료나 자동 migration으로 바꾸지 않는다.
        assertSame(failure, caught)
        assertTrue(output.isEmpty())
    }
}
