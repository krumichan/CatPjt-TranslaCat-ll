package jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

/** V001 스키마의 명시적 매핑이다. 이 객체로 테이블을 생성하거나 변경하지 않는다. */
internal object ListeningPoliciesTable : Table("language_learning_listening_policy_setting") {
    val id = varchar("id", 30)
    val enabled = bool("enabled")
    val defaultItemCount = integer("default_item_count")
    val minItemCount = integer("min_item_count")
    val maxItemCount = integer("max_item_count")
    val hardItemLimit = integer("hard_item_limit")
    val referenceAudioMaxSeconds = integer("reference_audio_max_seconds")
    val repeatAudioMaxSeconds = integer("repeat_audio_max_seconds")
    val maxAudioFileBytes = long("max_audio_file_bytes")
    val maxRerecordCount = integer("max_rerecord_count")
    val resumeHours = integer("resume_hours")
    val referenceAudioRetentionDays = integer("reference_audio_retention_days")
    val userAudioRetentionDays = integer("user_audio_retention_days")
    val reportedAudioRetentionDays = integer("reported_audio_retention_days")
    val automaticRetryLimit = integer("automatic_retry_limit")
    val manualRetryLimit = integer("manual_retry_limit")
    val practiceAttemptLimit = integer("practice_attempt_limit")
    val profilePolicyVersion = varchar("profile_policy_version", 100)
    val modelConfigVersion = varchar("model_config_version", 100)
    val referenceTtsRegenerationEnabled = bool("reference_tts_regeneration_enabled")
    val createdBy = varchar("created_by", 50).nullable()
    val createdAt = datetime("created_at")
    val updatedBy = varchar("updated_by", 50).nullable()
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(id)
}
