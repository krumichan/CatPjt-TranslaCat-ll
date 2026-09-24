package jp.co.translacat.languagelearning.shared.persistence

/** MIGRATE applies pending versions; VALIDATE refuses to start with pending versions. */
enum class MigrationMode {
    MIGRATE,
    VALIDATE;

    companion object {
        fun parse(value: String): MigrationMode = when (value) {
            "migrate" -> MIGRATE
            "validate" -> VALIDATE
            else -> throw IllegalArgumentException(
                "database.migrations.mode must be 'migrate' or 'validate'.",
            )
        }
    }
}
