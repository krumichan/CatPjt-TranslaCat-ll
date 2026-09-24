package jp.co.translacat.languagelearning.features.settings.domain.model

/** 관리자 singleton의 업무 값이다. 감사 정보와 Exposed 행을 외부에 노출하지 않는다. */
internal data class AdminSettings(
    val defaultDailySentenceCount: Int,
    val minDailySentenceCount: Int,
    val maxDailySentenceCount: Int,
    val dailyKeywordMaxCount: Int,
    val reviewAvailableDays: Int,
    val levelRecheckRecommendationDays: Int,
    val adaptiveWritingEnabled: Boolean,
    val aiEvaluationEnabled: Boolean,
    val speakingEnabled: Boolean,
    val speakingEvaluationEnabled: Boolean,
    val defaultDailySpeakingGoalMinutes: Int,
    val minDailySpeakingGoalMinutes: Int,
    val maxDailySpeakingGoalMinutes: Int,
    val dailySpeakingHardLimitMinutes: Int,
    val dailySpeakingSessionLimit: Int,
    val maxSessionMinutes: Int,
    val maxTurnsPerSession: Int,
    val minValidAudioSeconds: Double,
    val maxTurnAudioSeconds: Int,
    val maxAudioFileBytes: Long,
    val rawAudioRetentionDays: Int,
    val reportedAudioRetentionDays: Int,
    val activeSessionResumeHours: Int,
    val automaticRetryLimitPerStage: Int,
    val manualRetryLimitPerStage: Int,
    val sttTimeoutSeconds: Int,
    val ttsTimeoutSeconds: Int,
    val evaluationTimeoutSeconds: Int,
    val levelTestQuestionPoolTargetSize: Int?,
    val levelTestQuestionPoolReplenishmentEnabled: Boolean?,
) {
    // 기존 BE의 legacy null 처리와 같은 값이다. 임의의 관리자 변경값은 보정하지 않는다.
    fun resolvedQuestionPoolTarget(): Int = levelTestQuestionPoolTargetSize ?: 1_000
    fun resolvedQuestionPoolReplenishment(): Boolean = levelTestQuestionPoolReplenishmentEnabled ?: false
}
