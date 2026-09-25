package jp.co.translacat.languagelearning.features.leveltest.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

/** V007의 매핑이다. 테이블 생성과 변경은 Flyway만 수행한다. */
internal object LevelItemsTable : Table("language_learning_level_test_item") {
    val id = long("id").autoIncrement()
    val sessionId = long("session_id").references(LevelSessionsTable.id, fkName = "fk_ll_level_item_session")
    val number = integer("question_number")
    val payload = text("question_json")
    val status = varchar("status", 30)
    val poolId = long("pool_question_id").nullable()
    val modelAudioKey = varchar("model_answer_audio_key", 100).nullable()
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uk_ll_level_item_session_number", sessionId, number)
    }
}
