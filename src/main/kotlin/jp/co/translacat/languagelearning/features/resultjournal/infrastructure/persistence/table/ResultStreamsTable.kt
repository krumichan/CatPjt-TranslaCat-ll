package jp.co.translacat.languagelearning.features.resultjournal.infrastructure.persistence.table

import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.table.LearnersTable
import org.jetbrains.exposed.v1.core.Table

/** V006만 스키마를 생성한다. Core 테이블과 FK를 연결하지 않는다. */
internal object ResultStreamsTable : Table("language_learning_result_stream") {
    val sourceInstanceId = varchar("source_instance_id", 36)
    val userId = long("user_id").references(LearnersTable.userId)
    val lastSequence = long("last_sequence")
    override val primaryKey = PrimaryKey(sourceInstanceId, userId)
}
