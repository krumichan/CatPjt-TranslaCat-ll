package jp.co.translacat.languagelearning.bootstrap

import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings
import jp.co.translacat.languagelearning.shared.persistence.MigrationMode
import kotlin.system.exitProcess

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
        // SQL/비밀번호를 포함할 수 있는 예외 메시지를 외부 로그에 전달하지 않는다.
        System.err.println("LL_MIGRATION_FAILED: ${failure.javaClass.simpleName}")
        exitProcess(1)
    }
}
