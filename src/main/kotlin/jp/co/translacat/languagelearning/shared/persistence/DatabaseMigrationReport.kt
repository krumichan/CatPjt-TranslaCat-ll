package jp.co.translacat.languagelearning.shared.persistence

/** 기동 시 수행한 migration의 모드·현재 버전·적용 수를 기록한다. */
data class DatabaseMigrationReport(
    val mode: MigrationMode,
    val schemaVersion: String,
    val migrationsExecuted: Int,
)
