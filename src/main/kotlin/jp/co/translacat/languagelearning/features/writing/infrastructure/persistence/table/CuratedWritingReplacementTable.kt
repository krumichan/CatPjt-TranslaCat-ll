package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

/** 미답안 교체 전 원문을 보존한다. 사용자 계획 삭제 시에만 FK로 함께 삭제한다. */
internal object CuratedWritingReplacementTable : Table("language_learning_curated_writing_replacement") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val itemId = long("item_id")
    val requestKey = varchar("request_key", 80)
    val originalRevision = char("original_revision", 64)
    val replacementRevision = char("replacement_revision", 64)
    val originalPlanRevision = integer("original_plan_revision")
    val originalSnapshotJson = text("original_snapshot_json")
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(id)
}
