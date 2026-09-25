package jp.co.translacat.languagelearning.features.leveltest.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

/** V007의 매핑이다. 테이블 생성과 변경은 Flyway만 수행한다. */
internal object LevelCandidatesTable : Table("language_learning_level_test_question_candidate") {
    val id = long("id").autoIncrement()
    val sessionId = long("session_id").references(LevelSessionsTable.id, fkName = "fk_ll_level_candidate_session")
    val number = integer("question_number")
    val band = integer("complexity_band")
    val status = varchar("status", 30)
    val token = varchar("claim_token", 36).nullable()
    val leaseUntil = datetime("lease_until").nullable()
    val attempt = integer("attempt")
    val poolId =
        long("pool_question_id").references(LevelPoolTable.id, fkName = "fk_ll_level_candidate_pool").nullable()
    val reason = varchar("failure_code", 100).nullable()
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uk_ll_level_candidate_slot", sessionId, number, band)
    }
}
