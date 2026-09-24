package jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

/** V001 스키마의 명시적 매핑이다. 이 객체로 테이블을 생성하거나 변경하지 않는다. */
internal object LearnersTable : Table("language_learning_learner") {
    val userId = long("user_id")
    val status = varchar("status", 20)
    val identityVersion = long("identity_version")
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(userId)
}
