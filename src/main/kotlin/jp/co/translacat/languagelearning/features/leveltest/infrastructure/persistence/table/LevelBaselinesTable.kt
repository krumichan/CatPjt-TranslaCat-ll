package jp.co.translacat.languagelearning.features.leveltest.infrastructure.persistence.table

import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.table.LearnersTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.datetime

/** V007의 매핑이다. 테이블 생성과 변경은 Flyway만 수행한다. */
internal object LevelBaselinesTable : Table("language_learning_level_test_baseline") {
    val userId = long("user_id").references(LearnersTable.userId, fkName = "fk_ll_level_baseline_learner")
    val sessionId = long("session_id").references(LevelSessionsTable.id, fkName = "fk_ll_level_baseline_session")
    val completionId = varchar("completion_id", 36)
    val sessionType = varchar("session_type", 30)
    val score = integer("base_level_score")
    val band = varchar("proficiency_band", 40)
    val completedDate = date("completed_date")
    val startedAt = datetime("started_at")
    val completedAt = datetime("completed_at")
    override val primaryKey = PrimaryKey(userId)
}
