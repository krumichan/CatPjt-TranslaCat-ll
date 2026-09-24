package jp.co.translacat.languagelearning.features.settings.domain.policy

import jp.co.translacat.languagelearning.features.settings.domain.model.AdminSettings
import jp.co.translacat.languagelearning.features.settings.domain.model.AdminSettingsChange
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException

/** 최신 BE LanguageLearningAdminSetting.update의 범위/필드 간 제약을 유지한다. */
internal object AdminSettingsPolicy {
    fun change(current: AdminSettings, request: AdminSettingsChange): AdminSettings {
        val next = current.copy(
            defaultDailySentenceCount = request.defaultDailySentenceCount ?: current.defaultDailySentenceCount,
            minDailySentenceCount = request.minDailySentenceCount ?: current.minDailySentenceCount,
            maxDailySentenceCount = request.maxDailySentenceCount ?: current.maxDailySentenceCount,
            dailyKeywordMaxCount = request.dailyKeywordMaxCount ?: current.dailyKeywordMaxCount,
            reviewAvailableDays = request.reviewAvailableDays ?: current.reviewAvailableDays,
            levelRecheckRecommendationDays = request.levelRecheckRecommendationDays
                ?: current.levelRecheckRecommendationDays,
            adaptiveWritingEnabled = request.adaptiveWritingEnabled ?: current.adaptiveWritingEnabled,
            aiEvaluationEnabled = request.aiEvaluationEnabled ?: current.aiEvaluationEnabled,
            speakingEnabled = request.speakingEnabled ?: current.speakingEnabled,
            speakingEvaluationEnabled = request.speakingEvaluationEnabled ?: current.speakingEvaluationEnabled,
            defaultDailySpeakingGoalMinutes = request.defaultDailySpeakingGoalMinutes
                ?: current.defaultDailySpeakingGoalMinutes,
            minDailySpeakingGoalMinutes = request.minDailySpeakingGoalMinutes ?: current.minDailySpeakingGoalMinutes,
            maxDailySpeakingGoalMinutes = request.maxDailySpeakingGoalMinutes ?: current.maxDailySpeakingGoalMinutes,
            dailySpeakingHardLimitMinutes = request.dailySpeakingHardLimitMinutes
                ?: current.dailySpeakingHardLimitMinutes,
            dailySpeakingSessionLimit = request.dailySpeakingSessionLimit ?: current.dailySpeakingSessionLimit,
            maxSessionMinutes = request.maxSessionMinutes ?: current.maxSessionMinutes,
            maxTurnsPerSession = request.maxTurnsPerSession ?: current.maxTurnsPerSession,
            minValidAudioSeconds = request.minValidAudioSeconds ?: current.minValidAudioSeconds,
            maxTurnAudioSeconds = request.maxTurnAudioSeconds ?: current.maxTurnAudioSeconds,
            maxAudioFileBytes = request.maxAudioFileBytes ?: current.maxAudioFileBytes,
            rawAudioRetentionDays = request.rawAudioRetentionDays ?: current.rawAudioRetentionDays,
            reportedAudioRetentionDays = request.reportedAudioRetentionDays ?: current.reportedAudioRetentionDays,
            activeSessionResumeHours = request.activeSessionResumeHours ?: current.activeSessionResumeHours,
            automaticRetryLimitPerStage = request.automaticRetryLimitPerStage ?: current.automaticRetryLimitPerStage,
            manualRetryLimitPerStage = request.manualRetryLimitPerStage ?: current.manualRetryLimitPerStage,
            sttTimeoutSeconds = request.sttTimeoutSeconds ?: current.sttTimeoutSeconds,
            ttsTimeoutSeconds = request.ttsTimeoutSeconds ?: current.ttsTimeoutSeconds,
            evaluationTimeoutSeconds = request.evaluationTimeoutSeconds ?: current.evaluationTimeoutSeconds,
            levelTestQuestionPoolTargetSize = request.levelTestQuestionPoolTargetSize
                ?: current.resolvedQuestionPoolTarget(),
            levelTestQuestionPoolReplenishmentEnabled = request.levelTestQuestionPoolReplenishmentEnabled
                ?: current.levelTestQuestionPoolReplenishmentEnabled,
        )
        validate(next)
        return next
    }

    fun validate(v: AdminSettings) {
        val writingInvalid =
            v.minDailySentenceCount < 1 || v.maxDailySentenceCount < v.minDailySentenceCount || v.maxDailySentenceCount > 100 || v.defaultDailySentenceCount !in v.minDailySentenceCount..v.maxDailySentenceCount || v.dailyKeywordMaxCount !in 0..20 || v.reviewAvailableDays !in 1..365 || v.levelRecheckRecommendationDays !in 1..3650
        val goalInvalid =
            v.minDailySpeakingGoalMinutes < 1 || v.maxDailySpeakingGoalMinutes < v.minDailySpeakingGoalMinutes || v.defaultDailySpeakingGoalMinutes !in v.minDailySpeakingGoalMinutes..v.maxDailySpeakingGoalMinutes || v.dailySpeakingHardLimitMinutes < v.maxDailySpeakingGoalMinutes || v.dailySpeakingHardLimitMinutes > 240
        val sessionInvalid =
            v.dailySpeakingSessionLimit !in 1..100 || v.maxSessionMinutes !in 1..10 || v.maxTurnsPerSession !in 1..20
        // JSON으로 표현할 수 없는 NaN/Infinity도 내부 호출에서 명확히 거부한다.
        val audioInvalid =
            !v.minValidAudioSeconds.isFinite() || v.minValidAudioSeconds !in 0.1..10.0 || v.maxTurnAudioSeconds < v.minValidAudioSeconds || v.maxTurnAudioSeconds > 60 || v.maxAudioFileBytes !in 1024L..(10L * 1024 * 1024)
        val retentionInvalid =
            v.rawAudioRetentionDays !in 1..365 || v.reportedAudioRetentionDays < v.rawAudioRetentionDays || v.reportedAudioRetentionDays > 365
        val retryInvalid = v.automaticRetryLimitPerStage !in 0..2 || v.manualRetryLimitPerStage !in 0..1
        val timeoutInvalid =
            v.sttTimeoutSeconds < 1 || v.ttsTimeoutSeconds < 1 || v.evaluationTimeoutSeconds < 1 || v.activeSessionResumeHours !in 1..24
        if (writingInvalid || goalInvalid || sessionInvalid || audioInvalid || retentionInvalid || retryInvalid || timeoutInvalid || v.resolvedQuestionPoolTarget() !in 100..100_000) throw LearningBusinessException(
            UserSettingsPolicy.INVALID, "언어학습 관리자 설정값이 유효하지 않습니다.",
        )
    }
}
