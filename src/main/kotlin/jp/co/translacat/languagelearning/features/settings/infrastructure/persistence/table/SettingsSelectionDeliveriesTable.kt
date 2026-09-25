package jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.table

import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.table.LearnersTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

internal object SettingsSelectionDeliveriesTable : Table("language_learning_settings_selection_delivery") {
    val userId = long("user_id").references(
        LearnersTable.userId,
        onDelete = ReferenceOption.RESTRICT, onUpdate = ReferenceOption.RESTRICT,
        fkName = "fk_ll_selection_delivery_learner",
    )
    val lastEventId = long("last_event_id")
    val baseRevision = datetime("base_revision")
    val appliedRevision = datetime("applied_revision").nullable()
    override val primaryKey = PrimaryKey(userId)
}
