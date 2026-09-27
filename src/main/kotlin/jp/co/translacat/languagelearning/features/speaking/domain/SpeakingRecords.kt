package jp.co.translacat.languagelearning.features.speaking.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate
import java.time.LocalDateTime

@Serializable
internal enum class SpeakingSessionStatus { IN_PROGRESS, COMPLETED, EVALUATING, EVALUATED, EVALUATION_FAILED, EXPIRED }
@Serializable
internal enum class SpeakingEvaluationStatus { NOT_REQUESTED, PENDING, EVALUATING, EVALUATED, INSUFFICIENT_EVIDENCE, FAILED }
@Serializable
internal enum class SpeakingResultKind { SCORED_EVALUATION, SESSION_COACHING }
@Serializable
internal enum class SpeakingTurnStatus { AWAITING_UPLOAD, UPLOADED, PROCESSING, READY, PARTIAL_FAILURE, FAILED, EXCLUDED }
@Serializable
internal enum class SpeakingJobStatus { PENDING, RUNNING, SUCCEEDED, FAILED }

@Serializable
internal data class SpeakingTopic(
    val id: Long = 0,
    val topicCode: String,
    val category: String,
    val title: String,
    val description: String?,
    val originLanguage: String? = null,
    val learningLanguage: String? = null,
    val recommendedLevel: String?,
    val recommendedStartMode: ConversationStartMode,
    val active: Boolean = true,
    val sortOrder: Int,
    val version: Int = 1,
)

@Serializable
internal data class SpeakingSessionSnapshot(
    val topicId: Long?,
    val topicTitle: String,
    val topicCategory: String,
    val topicVersion: Int?,
    val customTopic: String?,
    val goal: String?,
    val persona: String?,
    val selectedKeywords: List<JsonObject>,
    val originLanguage: String,
    val learningLanguage: String,
    val practiceMode: SpeakingPracticeMode,
    val conversationStartMode: ConversationStartMode,
    val resolvedStartMode: ConversationStartMode,
    val correctionMode: CorrectionMode,
    val targetMinutes: Int,
    val maxTurns: Int,
    val voiceId: String,
    val playbackSpeed: String,
    val policy: SpeakingSessionPolicySnapshot,
    val learningProfile: JsonObject?,
    val resultKind: SpeakingResultKind,
    val resultPolicyVersion: String,
)

internal data class SpeakingSessionRecord(
    val id: Long = 0,
    val userId: Long,
    val createIdempotencyKey: String,
    val learningDate: LocalDate,
    val snapshot: SpeakingSessionSnapshot,
    val status: SpeakingSessionStatus = SpeakingSessionStatus.IN_PROGRESS,
    val evaluationStatus: SpeakingEvaluationStatus = SpeakingEvaluationStatus.NOT_REQUESTED,
    val completedTurns: Int = 0,
    val totalDurationSeconds: Long = 0,
    val opening: JsonObject = JsonObject(emptyMap()),
    val sessionSummary: String? = null,
    val usageSummary: JsonObject = JsonObject(emptyMap()),
    val evaluationVersion: String? = null,
    val startedAt: LocalDateTime,
    val completedAt: LocalDateTime? = null,
    val lastActivityAt: LocalDateTime = startedAt,
) {
    val openingState get() = (opening["_executionState"] as? JsonPrimitive)?.contentOrNull ?: "READY"
    val openingReady get() = openingState == "READY"
    val active get() = status == SpeakingSessionStatus.IN_PROGRESS && openingReady

    fun openingLeaseAlive(now: LocalDateTime): Boolean = openingState in setOf("PENDING", "RUNNING") &&
        (opening["_leaseUntil"] as? JsonPrimitive)?.contentOrNull?.let(LocalDateTime::parse)?.isAfter(now) == true

    fun expireIfNeeded(now: LocalDateTime): SpeakingSessionRecord =
        if (active && !SpeakingSessionPolicy.resumable(true, lastActivityAt, now, snapshot.policy)) {
            copy(status = SpeakingSessionStatus.EXPIRED, completedAt = now, lastActivityAt = now)
        } else this

    fun registerTurn(
        duration: Double, previousDuration: Double?, summary: String?, usage: JsonObject, now: LocalDateTime,
    ): SpeakingSessionRecord {
        // 재녹음 성공은 완료 수를 늘리지 않고 이전 녹음의 반올림 시간만 대체한다.
        val previous = previousDuration?.let { Math.round(it).coerceAtLeast(0) } ?: 0
        val next = Math.round(duration).coerceAtLeast(0)
        return copy(
            completedTurns = completedTurns + if (previousDuration == null) 1 else 0,
            totalDurationSeconds = (totalDurationSeconds - previous + next).coerceAtLeast(0),
            sessionSummary = summary ?: sessionSummary, usageSummary = usage, lastActivityAt = now,
        )
    }
}

@Serializable
internal data class SpeakingTurnContent(
    val durationSeconds: Double = 0.0,
    val transcript: String? = null,
    val sttConfidence: Double? = null,
    val sttSegments: List<JsonObject> = emptyList(),
    val sttMetadata: JsonObject = JsonObject(emptyMap()),
    val assistantText: String? = null,
    val conversation: JsonObject = JsonObject(emptyMap()),
    val assistanceUsage: List<SpeakingAssistanceType> = emptyList(),
    val usage: JsonObject = JsonObject(emptyMap()),
)

internal data class SpeakingTurnRecord(
    val id: Long = 0,
    val sessionId: Long,
    val turnIndex: Int,
    val idempotencyKey: String,
    val problemIndex: Int? = null,
    val attemptIndex: Int? = null,
    val recordingRevision: Int = 0,
    val status: SpeakingTurnStatus = SpeakingTurnStatus.AWAITING_UPLOAD,
    val uploadToken: String,
    val uploadExpiresAt: LocalDateTime,
    val content: SpeakingTurnContent = SpeakingTurnContent(),
    val excludedFromEvaluation: Boolean = false,
    val failedStage: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val manualRetryCount: Int = 0,
    val completedAt: LocalDateTime? = null,
    val executionToken: String? = null,
    val executionLeaseUntil: LocalDateTime? = null,
) {
    fun tokenValid(token: String?, now: LocalDateTime) = token == uploadToken && !uploadExpiresAt.isBefore(now)
    fun completionEvidence() = SpeakingCompletionTurn(
        content.transcript, content.durationSeconds,
        excludedFromEvaluation, status == SpeakingTurnStatus.AWAITING_UPLOAD,
    )
}

internal data class SpeakingJobRecord(
    val id: Long = 0,
    val sessionId: Long,
    val problemIndex: Int,
    val resultKind: SpeakingResultKind,
    val resultPolicyVersion: String,
    val sourceSnapshotHash: String?,
    val request: JsonObject,
    val status: SpeakingJobStatus = SpeakingJobStatus.PENDING,
    val claimToken: String? = null,
    val availableAt: LocalDateTime,
    val manualRetryCount: Int = 0,
    val recoveryCount: Int = 0,
    val lastError: String? = null,
) {
    init {
        require(problemIndex in 0..5)
    }

    fun due(now: LocalDateTime) =
        status in setOf(SpeakingJobStatus.PENDING, SpeakingJobStatus.RUNNING) && !availableAt.isAfter(now)

    fun owns(token: String?) = status == SpeakingJobStatus.RUNNING && token != null && token == claimToken
}

internal data class SpeakingResultRecord(
    val sessionId: Long,
    val problemIndex: Int,
    val resultKind: SpeakingResultKind,
    val status: String,
    val response: JsonObject,
    val updatedAt: LocalDateTime,
    val id: Long = 0,
)

internal data class SpeakingAudioRecord(
    val id: Long = 0,
    val sessionId: Long,
    val turnId: Long?,
    val role: String,
    val recordingRevision: Int,
    val objectKey: String,
    val contentType: String,
    val fileName: String?,
    val byteLength: Long,
    val sha256: String,
    val retentionUntil: LocalDateTime,
    val deletedAt: LocalDateTime? = null,
    val physicalDeletedAt: LocalDateTime? = null,
    val deleteClaimToken: String? = null,
    val deleteLeaseUntil: LocalDateTime? = null,
)

internal data class SpeakingSttReportRecord(
    val id: Long = 0,
    val userId: Long,
    val sessionId: Long,
    val turnId: Long,
    val reference: String,
    val reportType: String,
    val status: String = "OPEN",
    val expectedText: String?,
    val audioAnalysisConsent: Boolean,
    val audioRetentionUntil: LocalDateTime?,
    val sttMetadata: JsonObject,
    val clientMetadata: JsonElement,
    val supportRequested: Boolean,
    val supportReference: String?,
    val createdAt: LocalDateTime,
    val resolvedAt: LocalDateTime? = null,
)
