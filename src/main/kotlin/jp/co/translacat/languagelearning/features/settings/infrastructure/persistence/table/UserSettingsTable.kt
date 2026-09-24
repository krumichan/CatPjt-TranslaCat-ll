package jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.table

import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.table.LearnersTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.datetime

/** V001 스키마의 명시적 매핑이다. 이 객체로 테이블을 생성하거나 변경하지 않는다. */
internal object UserSettingsTable : Table("language_learning_user_setting") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
        .references(
            LearnersTable.userId,
            onDelete = ReferenceOption.RESTRICT,
            onUpdate = ReferenceOption.RESTRICT,
            fkName = "fk_ll_user_setting_learner",
        )
        .uniqueIndex("uk_language_learning_user_setting_user")
    val originLanguage = varchar("origin_language", 20).nullable()
    val learningLanguage = varchar("learning_language", 20).nullable()
    val timezone = varchar("timezone", 60)
    val dailySentenceCount = integer("daily_sentence_count")
    val dailySpeakingGoalMinutes = integer("daily_speaking_goal_minutes")
    val dailyListeningGoalCount = integer("daily_listening_goal_count")
    val defaultListeningTaskTypes = varchar("default_listening_task_types", 200)
    val speakingVoiceId = varchar("speaking_voice_id", 100)
    val speakingPlaybackSpeed = varchar("speaking_playback_speed", 20)
    val pendingOriginLanguage = varchar("pending_origin_language", 20).nullable()
    val pendingLearningLanguage = varchar("pending_learning_language", 20).nullable()
    val pendingTimezone = varchar("pending_timezone", 60).nullable()
    val pendingDailySentenceCount = integer("pending_daily_sentence_count").nullable()
    val pendingDailySpeakingGoalMinutes = integer("pending_daily_speaking_goal_minutes").nullable()
    val pendingDailyListeningGoalCount = integer("pending_daily_listening_goal_count").nullable()
    val pendingEffectiveDate = date("pending_effective_date").nullable()
    val createdBy = varchar("created_by", 50).nullable()
    val createdAt = datetime("created_at")
    val updatedBy = varchar("updated_by", 50).nullable()
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(id)
}
