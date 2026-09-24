package jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.table

import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.table.LearnersTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.datetime

/** V005 매핑이다. 테이블 생성/변경은 Flyway만 수행한다. */
internal object SystemKeywordSelectionsTable : Table("language_learning_user_system_keyword") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(
        LearnersTable.userId,
        onDelete = ReferenceOption.RESTRICT,
        onUpdate = ReferenceOption.RESTRICT,
        fkName = "fk_ll_user_keyword_learner"
    )
    val systemKeywordId = long("system_keyword_id").references(
        SystemKeywordsTable.id,
        onDelete = ReferenceOption.RESTRICT,
        onUpdate = ReferenceOption.RESTRICT,
        fkName = "fk_ll_user_keyword_system"
    )
    val active = bool("active")
    val availableFrom = date("available_from")
    val pendingActive = bool("pending_active").nullable()
    val pendingEffectiveDate = date("pending_effective_date").nullable()
    val createdBy = varchar("created_by", 50).nullable()
    val createdAt = datetime("created_at")
    val updatedBy = varchar("updated_by", 50).nullable()
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uk_ll_user_system_keyword", userId, systemKeywordId)
    }
}
