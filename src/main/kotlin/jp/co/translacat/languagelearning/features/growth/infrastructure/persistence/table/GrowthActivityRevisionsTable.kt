package jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.table

import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.table.LearnersTable
import org.jetbrains.exposed.v1.core.Table

/** 활동과 지표 저장 트랜잭션에 속한 사용자별 조회 버전이다. */
internal object GrowthActivityRevisionsTable : Table("language_learning_activity_revision") {
    val userId = long("user_id").references(LearnersTable.userId)
    val revision = long("revision")
    override val primaryKey = PrimaryKey(userId)
}
