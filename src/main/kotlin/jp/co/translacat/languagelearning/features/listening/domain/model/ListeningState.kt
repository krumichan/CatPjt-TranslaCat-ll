package jp.co.translacat.languagelearning.features.listening.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class ListeningSetState(
    val id: Long,
    val userId: Long,
    val learningDate: String,
    val originLanguage: String,
    val learningLanguage: String,
    val learningMode: String,
    val difficulty: String,
    val targetItemCount: Int,
    val request: JsonObject,
    val status: String = "GENERATING",
    val items: List<ListeningItemState> = emptyList(),
    val completedItemCount: Int = 0,
    val generationFailure: String? = null,
    val generationRetries: Int = 0,
    val revision: Long = 0,
)

@Serializable
internal data class ListeningItemState(
    val id: Long,
    val index: Int,
    val content: JsonObject,
    val status: String = "TTS_PENDING",
    val replacementSequence: Int = 0,
    val audioId: Long? = null,
    val audioDurationMs: Int? = null,
    val errorCode: String? = null,
    val ttsRetries: Int = 0,
)

@Serializable
internal data class ListeningSessionState(
    val id: Long,
    val userId: Long,
    val setId: Long,
    val idempotencyKey: String,
    val selectedTaskTypes: List<ListeningTaskType>,
    val startedAt: String,
    val lastActivityAt: String,
    val resumableUntil: String,
    val status: String = "IN_PROGRESS",
    val attempts: List<ListeningAttemptState> = emptyList(),
    val completedItemCount: Int = 0,
    val evaluatedItemCount: Int = 0,
    val actualDurationMs: Long = 0,
    val revision: Long = 0,
)

@Serializable
internal data class ListeningAttemptState(
    val id: Long,
    val itemId: Long,
    val itemIndex: Int,
    val attemptNo: Int,
    val purpose: String,
    val startedAt: String,
    val tasks: List<ListeningResponseState>,
    val status: String = "IN_PROGRESS",
    val answerRevealed: Boolean = false,
    val progressApplied: Boolean = false,
    val contentOverallScore: Double? = null,
    val listeningIndependenceScore: Double? = null,
    val overallScore: Double? = null,
    val evaluatedTaskCount: Int = 0,
    val coverage: Double = 0.0,
    val playbackEvents: Map<String, String> = emptyMap(),
    val submittedAt: String? = null,
    val submitKey: String? = null,
    val actualDurationMs: Long = 0,
    val idempotencyKey: String = "",
    val manualEvaluationRetries: Int = 0,
)

@Serializable
internal data class ListeningResponseState(
    val id: Long,
    val taskType: ListeningTaskType,
    val status: String = "PENDING",
    val answerText: String? = null,
    val audioId: Long? = null,
    val audioDurationMs: Int? = null,
    val rerecordCount: Int = 0,
    val revision: Long = 0,
    val assistanceUsage: List<ListeningAssistanceUsage> = emptyList(),
    val evaluation: ListeningTaskResult? = null,
    val evaluationHistory: List<ListeningTaskResult> = emptyList(),
    val evaluationRecords: List<ListeningEvaluationRecord> = emptyList(),
    val evaluatedAt: String? = null,
    val evaluationErrorCode: String? = null,
    val manualRetries: Int = 0,
    val idempotency: Map<String, String> = emptyMap(),
    val reports: List<ListeningReportState> = emptyList(),
)

@Serializable
internal data class ListeningEvaluationRecord(val id: Long, val evaluatedAt: String, val result: ListeningTaskResult)

@Serializable
internal data class ListeningReportState(
    val id: Long, val reasonCode: String, val comment: String?,
    val consentToRetainAudio: Boolean, val idempotencyKey: String,
    val createdAt: String,
)

internal data class ListeningJob(
    val id: Long, val userId: Long, val aggregateId: Long, val type: String,
    val key: String, val payload: JsonObject, val token: String?, val attemptCount: Int = 0,
    val status: String = "PENDING",
)

internal enum class ListeningJobFailure { STALE, RETRY, EXHAUSTED }
internal data class ListeningAudioState(
    val id: Long, val userId: Long, val ownerId: Long, val revision: Long,
    val contentType: String, val checksum: String, val bytes: ByteArray?,
    val retentionUntil: java.time.LocalDateTime, val deletedAt: java.time.LocalDateTime?,
)
