package jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.repository

import jp.co.translacat.languagelearning.features.settings.domain.exception.SettingsPolicyNotInitializedException
import jp.co.translacat.languagelearning.features.settings.domain.model.AdminSettings
import jp.co.translacat.languagelearning.features.settings.domain.repository.AdminSettingsRepository
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.table.AdminSettingsAuditsTable
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.table.AdminSettingsTable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDateTime

internal class ExposedAdminSettingsRepository(private val requireTransaction: () -> Unit) : AdminSettingsRepository {
    override fun loadForUpdate(): AdminSettings {
        requireTransaction()
        return AdminSettingsTable.selectAll()
            .where { AdminSettingsTable.id eq "DEFAULT" }
            .forUpdate()
            .singleOrNull()
            ?.let(::toModel) ?: throw SettingsPolicyNotInitializedException("admin DEFAULT")
    }

    override fun save(settings: AdminSettings, adminUserId: Long, nowUtc: LocalDateTime): AdminSettings {
        requireTransaction()
        val count = AdminSettingsTable.update({ AdminSettingsTable.id eq "DEFAULT" }) {
            it[defaultDailySentenceCount] = settings.defaultDailySentenceCount
            it[minDailySentenceCount] = settings.minDailySentenceCount
            it[maxDailySentenceCount] = settings.maxDailySentenceCount
            it[dailyKeywordMaxCount] = settings.dailyKeywordMaxCount
            it[reviewAvailableDays] = settings.reviewAvailableDays
            it[levelRecheckRecommendationDays] = settings.levelRecheckRecommendationDays
            it[adaptiveWritingEnabled] = settings.adaptiveWritingEnabled
            it[aiEvaluationEnabled] = settings.aiEvaluationEnabled
            it[speakingEnabled] = settings.speakingEnabled
            it[speakingEvaluationEnabled] = settings.speakingEvaluationEnabled
            it[defaultDailySpeakingGoalMinutes] = settings.defaultDailySpeakingGoalMinutes
            it[minDailySpeakingGoalMinutes] = settings.minDailySpeakingGoalMinutes
            it[maxDailySpeakingGoalMinutes] = settings.maxDailySpeakingGoalMinutes
            it[dailySpeakingHardLimitMinutes] = settings.dailySpeakingHardLimitMinutes
            it[dailySpeakingSessionLimit] = settings.dailySpeakingSessionLimit
            it[maxSessionMinutes] = settings.maxSessionMinutes
            it[maxTurnsPerSession] = settings.maxTurnsPerSession
            it[minValidAudioSeconds] = settings.minValidAudioSeconds
            it[maxTurnAudioSeconds] = settings.maxTurnAudioSeconds
            it[maxAudioFileBytes] = settings.maxAudioFileBytes
            it[rawAudioRetentionDays] = settings.rawAudioRetentionDays
            it[reportedAudioRetentionDays] = settings.reportedAudioRetentionDays
            it[activeSessionResumeHours] = settings.activeSessionResumeHours
            it[automaticRetryLimitPerStage] = settings.automaticRetryLimitPerStage
            it[manualRetryLimitPerStage] = settings.manualRetryLimitPerStage
            it[sttTimeoutSeconds] = settings.sttTimeoutSeconds
            it[ttsTimeoutSeconds] = settings.ttsTimeoutSeconds
            it[evaluationTimeoutSeconds] = settings.evaluationTimeoutSeconds
            it[levelTestQuestionPoolTargetSize] = settings.levelTestQuestionPoolTargetSize
            it[levelTestQuestionPoolReplenishmentEnabled] = settings.levelTestQuestionPoolReplenishmentEnabled
            it[updatedBy] = adminUserId.toString()
            it[updatedAt] = nowUtc
        }
        check(count in 0..1) { "관리자 설정 갱신 행 수가 올바르지 않습니다." }
        return loadForUpdate().also { check(it == settings) { "관리자 설정 저장 결과가 다릅니다." } }
    }

    override fun appendAudit(adminUserId: Long, before: AdminSettings, after: AdminSettings, nowUtc: LocalDateTime) {
        requireTransaction()
        AdminSettingsAuditsTable.insert {
            it[AdminSettingsAuditsTable.adminUserId] = adminUserId
            it[beforeJson] = snapshot(before)
            it[afterJson] = snapshot(after)
            it[createdBy] = adminUserId.toString()
            it[createdAt] = nowUtc
        }
    }

    private fun toModel(row: ResultRow) = AdminSettings(
        defaultDailySentenceCount = row[AdminSettingsTable.defaultDailySentenceCount],
        minDailySentenceCount = row[AdminSettingsTable.minDailySentenceCount],
        maxDailySentenceCount = row[AdminSettingsTable.maxDailySentenceCount],
        dailyKeywordMaxCount = row[AdminSettingsTable.dailyKeywordMaxCount],
        reviewAvailableDays = row[AdminSettingsTable.reviewAvailableDays],
        levelRecheckRecommendationDays = row[AdminSettingsTable.levelRecheckRecommendationDays],
        adaptiveWritingEnabled = row[AdminSettingsTable.adaptiveWritingEnabled],
        aiEvaluationEnabled = row[AdminSettingsTable.aiEvaluationEnabled],
        speakingEnabled = row[AdminSettingsTable.speakingEnabled],
        speakingEvaluationEnabled = row[AdminSettingsTable.speakingEvaluationEnabled],
        defaultDailySpeakingGoalMinutes = row[AdminSettingsTable.defaultDailySpeakingGoalMinutes],
        minDailySpeakingGoalMinutes = row[AdminSettingsTable.minDailySpeakingGoalMinutes],
        maxDailySpeakingGoalMinutes = row[AdminSettingsTable.maxDailySpeakingGoalMinutes],
        dailySpeakingHardLimitMinutes = row[AdminSettingsTable.dailySpeakingHardLimitMinutes],
        dailySpeakingSessionLimit = row[AdminSettingsTable.dailySpeakingSessionLimit],
        maxSessionMinutes = row[AdminSettingsTable.maxSessionMinutes],
        maxTurnsPerSession = row[AdminSettingsTable.maxTurnsPerSession],
        minValidAudioSeconds = row[AdminSettingsTable.minValidAudioSeconds],
        maxTurnAudioSeconds = row[AdminSettingsTable.maxTurnAudioSeconds],
        maxAudioFileBytes = row[AdminSettingsTable.maxAudioFileBytes],
        rawAudioRetentionDays = row[AdminSettingsTable.rawAudioRetentionDays],
        reportedAudioRetentionDays = row[AdminSettingsTable.reportedAudioRetentionDays],
        activeSessionResumeHours = row[AdminSettingsTable.activeSessionResumeHours],
        automaticRetryLimitPerStage = row[AdminSettingsTable.automaticRetryLimitPerStage],
        manualRetryLimitPerStage = row[AdminSettingsTable.manualRetryLimitPerStage],
        sttTimeoutSeconds = row[AdminSettingsTable.sttTimeoutSeconds],
        ttsTimeoutSeconds = row[AdminSettingsTable.ttsTimeoutSeconds],
        evaluationTimeoutSeconds = row[AdminSettingsTable.evaluationTimeoutSeconds],
        levelTestQuestionPoolTargetSize = row[AdminSettingsTable.levelTestQuestionPoolTargetSize],
        levelTestQuestionPoolReplenishmentEnabled = row[AdminSettingsTable.levelTestQuestionPoolReplenishmentEnabled],
    )

    // API 클래스에 의존하지 않되 BE 응답과 동일한 필드명/legacy null 해석으로 기록한다.
    private fun snapshot(v: AdminSettings): String = buildJsonObject {
        put("defaultDailySentenceCount", v.defaultDailySentenceCount)
        put("minDailySentenceCount", v.minDailySentenceCount)
        put("maxDailySentenceCount", v.maxDailySentenceCount)
        put("dailyKeywordMaxCount", v.dailyKeywordMaxCount)
        put("reviewAvailableDays", v.reviewAvailableDays)
        put("levelRecheckRecommendationDays", v.levelRecheckRecommendationDays)
        put("adaptiveWritingEnabled", v.adaptiveWritingEnabled)
        put("aiEvaluationEnabled", v.aiEvaluationEnabled)
        put("speakingEnabled", v.speakingEnabled)
        put("speakingEvaluationEnabled", v.speakingEvaluationEnabled)
        put("defaultDailySpeakingGoalMinutes", v.defaultDailySpeakingGoalMinutes)
        put("minDailySpeakingGoalMinutes", v.minDailySpeakingGoalMinutes)
        put("maxDailySpeakingGoalMinutes", v.maxDailySpeakingGoalMinutes)
        put("dailySpeakingHardLimitMinutes", v.dailySpeakingHardLimitMinutes)
        put("dailySpeakingSessionLimit", v.dailySpeakingSessionLimit)
        put("maxSessionMinutes", v.maxSessionMinutes)
        put("maxTurnsPerSession", v.maxTurnsPerSession)
        put("minValidAudioSeconds", v.minValidAudioSeconds)
        put("maxTurnAudioSeconds", v.maxTurnAudioSeconds)
        put("maxAudioFileBytes", v.maxAudioFileBytes)
        put("rawAudioRetentionDays", v.rawAudioRetentionDays)
        put("reportedAudioRetentionDays", v.reportedAudioRetentionDays)
        put("activeSessionResumeHours", v.activeSessionResumeHours)
        put("automaticRetryLimitPerStage", v.automaticRetryLimitPerStage)
        put("manualRetryLimitPerStage", v.manualRetryLimitPerStage)
        put("sttTimeoutSeconds", v.sttTimeoutSeconds)
        put("ttsTimeoutSeconds", v.ttsTimeoutSeconds)
        put("evaluationTimeoutSeconds", v.evaluationTimeoutSeconds)
        put("levelTestQuestionPoolTargetSize", v.resolvedQuestionPoolTarget())
        put("levelTestQuestionPoolReplenishmentEnabled", v.resolvedQuestionPoolReplenishment())
    }.toString()
}
