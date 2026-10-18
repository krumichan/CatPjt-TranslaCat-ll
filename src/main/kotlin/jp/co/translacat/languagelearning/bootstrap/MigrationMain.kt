package jp.co.translacat.languagelearning.bootstrap

import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.DatabaseMigrationReport
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings
import jp.co.translacat.languagelearning.shared.persistence.MigrationMode
import org.flywaydb.core.api.FlywayException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.sql.SQLException
import java.sql.SQLInvalidAuthorizationSpecException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTimeoutException
import java.sql.SQLTransientConnectionException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException
import kotlin.system.exitProcess

private val safeSqlStatePattern = Regex("[A-Za-z0-9]{5}")

/** 원문 메시지는 노출하지 않고 타입/JDBC 메타데이터/고정 진단 코드만 출력한다. */
internal fun migrationFailureDiagnosticLines(failure: Throwable): List<String> {
    // 루트와 최대 8개 원인을 수집하며 순환하는 예외 체인은 한 번만 방문한다.
    val chain = mutableListOf<Throwable>()
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var cause: Throwable? = failure
    while (cause != null && chain.size <= 8 && visited.add(cause)) {
        chain += cause
        cause = cause.cause
    }

    // 연결/TLS 실패를 권한 또는 migration SQL 실패와 구분한다.
    val lines = mutableListOf(
        "LL_MIGRATION_FAILED: ${failure.javaClass.simpleName}",
        "LL_MIGRATION_CATEGORY: ${migrationFailureCategory(chain)}",
    )
    chain.forEachIndexed { index, current ->
        if (index > 0) {
            lines += "LL_MIGRATION_CAUSE_$index: ${current.javaClass.simpleName}"
        }
        if (current is SQLException) {
            val sqlState = current.sqlState?.takeIf { safeSqlStatePattern.matches(it) } ?: "UNKNOWN"
            lines += "LL_MIGRATION_SQLSTATE_$index: $sqlState"
            lines += "LL_MIGRATION_SQLCODE_$index: ${current.errorCode}"
        }
        if (current is SocketException) {
            lines += "LL_MIGRATION_SOCKET_REASON_$index: ${socketFailureReason(current)}"
        }
    }
    return lines
}

private fun migrationFailureCategory(chain: List<Throwable>): String = when {
    chain.any { it is SSLException } -> "TLS"
    chain.any {
        it is SQLInvalidAuthorizationSpecException ||
            (it is SQLException && (it.sqlState?.startsWith("28") == true || it.errorCode in setOf(1044, 1045)))
    } -> "AUTHENTICATION"
    chain.any { it is SocketTimeoutException || it is SQLTimeoutException } -> "TIMEOUT"
    chain.any {
        it is SocketException || it is UnknownHostException ||
            it is SQLTransientConnectionException || it is SQLNonTransientConnectionException ||
            (it is SQLException && it.sqlState?.startsWith("08") == true)
    } -> "CONNECTION"
    chain.any { it is SQLException || it is FlywayException } -> "SCHEMA_OR_MIGRATION"
    else -> "UNKNOWN"
}

private fun socketFailureReason(failure: SocketException): String = when (failure.message) {
    // 완전히 일치하는 운영체제 메시지만 고정 코드로 바꾼다. 접속 대상 등의 원문은 출력하지 않는다.
    "Connection reset", "Connection reset by peer" -> "RESET"
    "Software caused connection abort", "Software caused connection abort: recv failed" -> "ABORTED"
    "Broken pipe" -> "BROKEN_PIPE"
    "Permission denied", "Operation not permitted" -> "PERMISSION_DENIED"
    "Network is unreachable", "Network unreachable" -> "NETWORK_UNREACHABLE"
    "No route to host", "Host is unreachable" -> "HOST_UNREACHABLE"
    "Connection refused" -> "REFUSED"
    "Connection timed out", "Read timed out" -> "TIMED_OUT"
    else -> "UNKNOWN"
}

/** 명시적 읽기 전용 인자는 DDL 승인을 요구하지 않는다. 다른 인자는 연결 전에 거절한다. */
internal fun databaseCommandSettings(args: Array<String>, environment: (String) -> String?): DatabaseSettings {
    // 빈 인자의 기존 이행 경로와 단일 validate 명령만 허용한다. 환경변수로 모드를 바꾸지 않는다.
    val mode = when {
        args.isEmpty() -> MigrationMode.MIGRATE
        args.contentEquals(arrayOf("--validate-database")) -> MigrationMode.VALIDATE
        else -> throw IllegalArgumentException("Unsupported database command arguments.")
    }
    if (mode == MigrationMode.MIGRATE) {
        check(environment("LL_ALLOW_MIGRATIONS") == "true") { "Migration approval is required." }
    }

    // validate에는 배포 도구가 런타임 DB 계정을 전달한다. URL과 catalog의 기존 검사는 유지한다.
    fun required(name: String) = checkNotNull(environment(name)?.takeIf(String::isNotBlank)) { name }
    return DatabaseSettings(
        enabled = true,
        jdbcUrl = required("DB_JDBC_URL"),
        username = required("DB_USERNAME"),
        password = required("DB_PASSWORD"),
        expectedCatalog = "translacat_ll",
        maximumPoolSize = 2,
        minimumIdle = 0,
        migrationMode = mode,
    ).also(DatabaseSettings::validateForConnection)
}

/** 동일한 strict DB 초기화만 실행하고 pool을 닫는다. 애플리케이션 DI와 작업자는 시작하지 않는다. */
internal fun runDatabaseCommand(
    args: Array<String>,
    environment: (String) -> String? = System::getenv,
    connect: (DatabaseSettings) -> DatabaseMigrationReport = { settings ->
        DatabaseFactory(settings).use { it.migrationReport }
    },
    output: (String) -> Unit = ::println,
) {
    // 인자·승인·접속 대상 검사가 끝나기 전에는 연결 함수를 호출하지 않는다.
    val settings = databaseCommandSettings(args, environment)
    val report = connect(settings)
    check(report.mode == settings.migrationMode) { "Database command mode mismatch." }

    // 복구 호환성은 실제 strict 검증의 결과만 고정 코드로 보고한다. 주소·SQL·자격증명은 출력하지 않는다.
    if (settings.migrationMode == MigrationMode.VALIDATE) {
        check(report.migrationsExecuted == 0) { "Validation must not apply migrations." }
        check(report.schemaVersion.matches(Regex("[0-9]{1,9}")) && report.schemaVersion.toInt() > 0) {
            "Invalid database schema version."
        }
        output("LL_DATABASE_VALIDATION_COMPLETE")
        output("LL_DATABASE_SCHEMA_VERSION: ${report.schemaVersion}")
        output("LL_DATABASE_MIGRATIONS_EXECUTED: 0")
    } else {
        output("LL_MIGRATION_COMPLETE")
    }
}

/** 승인된 배포 또는 명시적 호환성 검증 전용. API와 AI 작업자를 시작하지 않는다. */
fun main(args: Array<String>) {
    try {
        runDatabaseCommand(args)
    } catch (failure: Exception) {
        migrationFailureDiagnosticLines(failure).forEach(System.err::println)
        exitProcess(1)
    }
}
