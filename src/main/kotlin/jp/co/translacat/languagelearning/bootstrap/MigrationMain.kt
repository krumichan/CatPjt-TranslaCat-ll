package jp.co.translacat.languagelearning.bootstrap

import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings
import jp.co.translacat.languagelearning.shared.persistence.MigrationMode
import java.sql.SQLException
import kotlin.system.exitProcess

private val safeSqlStatePattern = Regex("[A-Za-z0-9]{5}")

/** 실패 메시지/URL/비밀번호는 노출하지 않고 예외 타입과 JDBC 메타데이터만 진단한다. */
internal fun migrationFailureDiagnosticLines(failure: Throwable): List<String> {
    val lines = mutableListOf("LL_MIGRATION_FAILED: ${failure.javaClass.simpleName}")
    var cause = failure.cause
    var index = 1
    while (cause != null && index <= 8) {
        lines += "LL_MIGRATION_CAUSE_$index: ${cause.javaClass.simpleName}"
        if (cause is SQLException) {
            val sqlState = cause.sqlState?.takeIf { safeSqlStatePattern.matches(it) } ?: "UNKNOWN"
            lines += "LL_MIGRATION_SQLSTATE_$index: $sqlState"
            lines += "LL_MIGRATION_SQLCODE_$index: ${cause.errorCode}"
        }
        cause = cause.cause
        index++
    }
    return lines
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
