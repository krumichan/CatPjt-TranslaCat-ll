package jp.co.translacat.languagelearning.features.leveltest.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

/** V007의 매핑이다. 테이블 생성과 변경은 Flyway만 수행한다. */
internal object LevelPoolTable : Table("language_learning_level_test_question_pool") {
    val id = long("id").autoIncrement()
    val poolKey = varchar("pool_key", 64).uniqueIndex("uk_ll_level_pool_key")
    val originLanguage = varchar("origin_language", 20)
    val learningLanguage = varchar("learning_language", 20)
    val payload = text("question_json")
    val active = bool("active")
    val reason = varchar("quarantine_reason", 100).nullable()
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(id)
}
