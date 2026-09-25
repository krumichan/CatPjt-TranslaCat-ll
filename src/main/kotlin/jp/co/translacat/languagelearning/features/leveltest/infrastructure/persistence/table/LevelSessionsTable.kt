package jp.co.translacat.languagelearning.features.leveltest.infrastructure.persistence.table

import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.table.LearnersTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.datetime

/** V007의 매핑이다. 테이블 생성과 변경은 Flyway만 수행한다. */
internal object LevelSessionsTable : Table("language_learning_level_test_session") {
    val id = long("id").autoIncrement()
    val uid = varchar("session_uid", 36).uniqueIndex("uk_ll_level_session_uid")
    val userId = long("user_id").references(
        LearnersTable.userId, onDelete = ReferenceOption.RESTRICT, onUpdate = ReferenceOption.RESTRICT,
        fkName = "fk_ll_level_session_learner",
    )
    val sessionType = varchar("session_type", 30)
    val status = varchar("status", 30)
    val originLanguage = varchar("origin_language", 20)
    val learningLanguage = varchar("learning_language", 20)
    val timezone = varchar("timezone", 60)
    val number = integer("current_question_number")
    val band = integer("current_complexity_band")
    val baseScore = integer("base_level_score").nullable()
    val proficiencyBand = varchar("proficiency_band", 40).nullable()
    val domainScores = text("domain_scores_json")
    val startedAt = datetime("started_at")
    val lastActivityAt = datetime("last_activity_at")
    val completedAt = datetime("completed_at").nullable()
    val completedDate = date("completed_date").nullable()
    val idempotencyKey = varchar("idempotency_key", 200)
    val operationToken = varchar("operation_token", 36).nullable()
    val operationKind = varchar("operation_kind", 30).nullable()
    val leaseUntil = datetime("lease_until").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uk_ll_level_session_user_key", userId, idempotencyKey); index(
            "idx_ll_level_session_user_status", false, userId, status,
        )
    }
}
