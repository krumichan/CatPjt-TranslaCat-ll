package jp.co.translacat.languagelearning.features.leveltest.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

/** V007의 매핑이다. 테이블 생성과 변경은 Flyway만 수행한다. */
internal object LevelEvaluationsTable : Table("language_learning_level_test_evaluation") {
    val responseId = long("response_id").references(LevelResponsesTable.id, fkName = "fk_ll_level_evaluation_response")
    val payload = text("evaluation_json")
    val evaluatedAt = datetime("evaluated_at")
    override val primaryKey = PrimaryKey(responseId)
}
