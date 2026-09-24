package jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

/** V003에서만 생성한다. Core 사용자 테이블과 FK를 연결하지 않는다. */
internal object AdminSettingsAuditsTable : Table("language_learning_admin_setting_audit") {
    val id = long("id").autoIncrement()
    val adminUserId = long("admin_user_id").nullable()
    val beforeJson = text("before_json")
    val afterJson = text("after_json")
    val createdBy = varchar("created_by", 50).nullable()
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(id)
}
