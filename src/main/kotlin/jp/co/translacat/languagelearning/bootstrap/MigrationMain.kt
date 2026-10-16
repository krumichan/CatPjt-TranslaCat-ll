package jp.co.translacat.languagelearning.bootstrap

import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
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

/** 승인된 배포 작업 전용. API와 AI 작업자를 시작하지 않는다. */
fun main() {
    try {
        check(System.getenv("LL_ALLOW_MIGRATIONS") == "true") { "Migration approval is required." }
        fun required(name: String) = checkNotNull(System.getenv(name)?.takeIf(String::isNotBlank)) { name }
        val settings = DatabaseSettings(
            enabled = true,
            jdbcUrl = required("DB_JDBC_URL"),
            username = required("DB_USERNAME"),
            password = required("DB_PASSWORD"),
            expectedCatalog = "translacat_ll",
            maximumPoolSize = 2,
            minimumIdle = 0,
            migrationMode = MigrationMode.MIGRATE,
        )
        DatabaseFactory(settings).use { println("LL_MIGRATION_COMPLETE") }
    } catch (failure: Exception) {
        migrationFailureDiagnosticLines(failure).forEach(System.err::println)
        exitProcess(1)
    }
}
