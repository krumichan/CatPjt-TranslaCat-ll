package jp.co.translacat.languagelearning.features.speaking.domain

import jp.co.translacat.languagelearning.features.settings.domain.model.AdminSettings
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.util.*

internal class SpeakingFailure(val code: String, val status: Int = 400) : RuntimeException(code)

@Serializable
internal data class SpeakingCreateRequest(
    val topicId: Long? = null,
    val keywordBasedTopic: Boolean = false,
    val customTopic: String? = null,
    val goal: String? = null,
    val persona: String? = null,
    val practiceMode: SpeakingPracticeMode? = null,
    val conversationStartMode: ConversationStartMode? = null,
    val correctionMode: CorrectionMode? = null,
    val targetMinutes: Int = 0,
    val voiceId: String? = null,
    val playbackSpeed: String? = null,
    val idempotencyKey: String? = null,
)

@Serializable
internal data class SpeakingSessionPolicySnapshot(
    val speakingEvaluationEnabled: Boolean,
    val dailySpeakingHardLimitMinutes: Int,
    val dailySpeakingSessionLimit: Int,
    val maxSessionMinutes: Int,
    val maxTurns: Int,
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
) {
    val maxSessionSeconds get() = maxSessionMinutes * 60L
    val dailySpeakingHardLimitSeconds get() = dailySpeakingHardLimitMinutes * 60L

    companion object {
        fun from(admin: AdminSettings) = SpeakingSessionPolicySnapshot(
            admin.speakingEvaluationEnabled, admin.dailySpeakingHardLimitMinutes, admin.dailySpeakingSessionLimit,
            admin.maxSessionMinutes, admin.maxTurnsPerSession, admin.minValidAudioSeconds, admin.maxTurnAudioSeconds,
            admin.maxAudioFileBytes, admin.rawAudioRetentionDays, admin.reportedAudioRetentionDays,
            admin.activeSessionResumeHours, admin.automaticRetryLimitPerStage, admin.manualRetryLimitPerStage,
            admin.sttTimeoutSeconds, admin.ttsTimeoutSeconds, admin.evaluationTimeoutSeconds,
        )
    }
}

internal object SpeakingSessionPolicy {
    const val READ_ALOUD_DAILY_ITEM_COUNT = 5
    const val READ_ALOUD_REQUIRED_ATTEMPTS_PER_ITEM = 2
    const val READ_ALOUD_MAX_ATTEMPTS_PER_ITEM = 3

    fun validateCreate(request: SpeakingCreateRequest?, admin: AdminSettings) {
        // 기능 토글과 topic 공급원을 기존 순서대로 확인한다.
        if (!admin.speakingEnabled) throw SpeakingFailure("SPEAKING_DISABLED")
        if (request == null) invalid()
        val sources = listOf(
            request.topicId != null, request.keywordBasedTopic, !request.customTopic.isNullOrBlank(),
        ).count { it }
        if (sources != 1) invalid()
        if (request.keywordBasedTopic && request.conversationStartMode != ConversationStartMode.AI_FIRST) invalid()

        // 세션 길이·필수 모드·멱등 키를 검사한 뒤 선택적 문자열의 기존 범위를 보존한다.
        if (request.targetMinutes !in admin.minDailySpeakingGoalMinutes..admin.maxDailySpeakingGoalMinutes) invalid()
        if (request.practiceMode == null || request.conversationStartMode == null || request.correctionMode == null) invalid()
        if (request.idempotencyKey.isNullOrBlank() || request.idempotencyKey.length > 200) invalid()
        if (request.voiceId != null && request.voiceId.length > 100) invalid()
        if (request.playbackSpeed != null && request.playbackSpeed.trim().uppercase(Locale.ROOT) !in setOf(
                "NORMAL", "SLOW",
            )
        ) invalid()
        if (request.customTopic != null && (request.customTopic.trim()
                .isBlank() || request.customTopic.trim().length > 500)
        ) invalid()
    }

    fun maxTurns(mode: SpeakingPracticeMode, configured: Int): Int =
        if (mode == SpeakingPracticeMode.READ_ALOUD) READ_ALOUD_DAILY_ITEM_COUNT * READ_ALOUD_MAX_ATTEMPTS_PER_ITEM else configured

    fun resolveStart(requested: ConversationStartMode, recommended: ConversationStartMode?): ConversationStartMode =
        if (requested != ConversationStartMode.TOPIC_RECOMMENDED) requested
        else recommended?.takeUnless { it == ConversationStartMode.TOPIC_RECOMMENDED } ?: ConversationStartMode.AI_FIRST

    fun requireResolvedStart(mode: SpeakingPracticeMode?, resolved: ConversationStartMode?) {
        if (mode == null || resolved == null) invalid()
        if (mode != SpeakingPracticeMode.FREE && resolved != ConversationStartMode.AI_FIRST) invalid()
    }

    fun resumable(
        active: Boolean, lastActivityAt: LocalDateTime, now: LocalDateTime, snapshot: SpeakingSessionPolicySnapshot,
    ): Boolean =
        active && !lastActivityAt.isBefore(now.minusHours(snapshot.activeSessionResumeHours.toLong()))

    fun requireRemainingLimit(sessionDurations: List<Long>, admin: AdminSettings) {
        // 생성 시점은 기존 Core와 같이 분 단위 표시값을 반올림한 뒤 일일 한도에 대조한다.
        val usedMinutes = Math.round(sessionDurations.sum() / 60.0 * 100.0) / 100.0
        if (sessionDurations.size >= admin.dailySpeakingSessionLimit || usedMinutes >= admin.dailySpeakingHardLimitMinutes) {
            throw SpeakingFailure("DAILY_LIMIT_EXCEEDED")
        }
    }

    fun requireTurnAllowed(usedSeconds: Long, additionalSeconds: Long, snapshot: SpeakingSessionPolicySnapshot) {
        if (usedSeconds + additionalSeconds.coerceAtLeast(0) > snapshot.dailySpeakingHardLimitSeconds) {
            throw SpeakingFailure("DAILY_LIMIT_EXCEEDED")
        }
    }

    fun shouldComplete(
        active: Boolean,
        mode: SpeakingPracticeMode,
        aiRequestedEnd: Boolean,
        completedTurns: Int,
        maxTurns: Int,
        durationSeconds: Long,
        snapshot: SpeakingSessionPolicySnapshot,
    ): Boolean {
        if (!active) return false
        return aiRequestedEnd || (mode != SpeakingPracticeMode.READ_ALOUD && completedTurns >= maxTurns) ||
            durationSeconds >= snapshot.maxSessionSeconds
    }

    private fun invalid(): Nothing = throw SpeakingFailure("LANGUAGE_LEARNING_SETTING_INVALID", 400)
}
