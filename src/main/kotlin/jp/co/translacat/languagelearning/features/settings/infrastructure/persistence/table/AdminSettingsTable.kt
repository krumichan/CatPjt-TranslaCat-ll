package jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

/** V001 스키마의 명시적 매핑이다. 이 객체로 테이블을 생성하거나 변경하지 않는다. */
internal object AdminSettingsTable : Table("language_learning_admin_setting") {
    val id = varchar("id", 30)
    val defaultDailySentenceCount = integer("default_daily_sentence_count")
    val minDailySentenceCount = integer("min_daily_sentence_count")
    val maxDailySentenceCount = integer("max_daily_sentence_count")
    val dailyKeywordMaxCount = integer("daily_keyword_max_count")
    val reviewAvailableDays = integer("review_available_days")
    val levelRecheckRecommendationDays = integer("level_recheck_recommendation_days")
    val adaptiveWritingEnabled = bool("adaptive_writing_enabled")
    val aiEvaluationEnabled = bool("ai_evaluation_enabled")
    val speakingEnabled = bool("speaking_enabled")
    val speakingEvaluationEnabled = bool("speaking_evaluation_enabled")
    val defaultDailySpeakingGoalMinutes = integer("default_daily_speaking_goal_minutes")
    val minDailySpeakingGoalMinutes = integer("min_daily_speaking_goal_minutes")
    val maxDailySpeakingGoalMinutes = integer("max_daily_speaking_goal_minutes")
    val dailySpeakingHardLimitMinutes = integer("daily_speaking_hard_limit_minutes")
    val dailySpeakingSessionLimit = integer("daily_speaking_session_limit")
    val maxSessionMinutes = integer("max_session_minutes")
    val maxTurnsPerSession = integer("max_turns_per_session")
    val minValidAudioSeconds = double("min_valid_audio_seconds")
    val maxTurnAudioSeconds = integer("max_turn_audio_seconds")
    val maxAudioFileBytes = long("max_audio_file_bytes")
    val rawAudioRetentionDays = integer("raw_audio_retention_days")
    val reportedAudioRetentionDays = integer("reported_audio_retention_days")
    val activeSessionResumeHours = integer("active_session_resume_hours")
    val automaticRetryLimitPerStage = integer("automatic_retry_limit_per_stage")
    val manualRetryLimitPerStage = integer("manual_retry_limit_per_stage")
    val sttTimeoutSeconds = integer("stt_timeout_seconds")
    val ttsTimeoutSeconds = integer("tts_timeout_seconds")
    val evaluationTimeoutSeconds = integer("evaluation_timeout_seconds")
    val levelTestQuestionPoolTargetSize = integer("level_test_question_pool_target_size").nullable()
    val levelTestQuestionPoolReplenishmentEnabled = bool("level_test_question_pool_replenishment_enabled").nullable()
    val createdBy = varchar("created_by", 50).nullable()
    val createdAt = datetime("created_at")
    val updatedBy = varchar("updated_by", 50).nullable()
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(id)
}
