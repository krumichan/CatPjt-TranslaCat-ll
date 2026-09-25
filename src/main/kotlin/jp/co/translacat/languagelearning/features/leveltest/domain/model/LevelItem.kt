package jp.co.translacat.languagelearning.features.leveltest.domain.model

import java.time.LocalDateTime

internal data class LevelItem(
    val id: Long = 0,
    val sessionId: Long,
    val questionNumber: Int,
    val data: LevelQuestionData,
    val status: LevelTestItemStatus = LevelTestItemStatus.READY,
    val poolQuestionId: Long? = null,
    val modelAnswerAudioKey: String? = null,
    val createdAt: LocalDateTime,
)

internal data class LevelSubmission(
    val id: Long = 0,
    val itemId: Long,
    val idempotencyKey: String,
    val fingerprint: String,
    val selectedOptionKey: String? = null,
    val selectedOptionKeys: List<String> = emptyList(),
    val textAnswer: String? = null,
    val audioKey: String? = null,
    val audioContentType: String? = null,
    val audioDurationMs: Int? = null,
    val audioRetentionUntil: LocalDateTime? = null,
    val submittedAt: LocalDateTime,
    val manualRetryCount: Int = 0,
    val revision: Int = 1,
)

internal data class LevelEvaluation(
    val responseId: Long,
    val data: LevelEvaluationData,
    val evaluatedAt: LocalDateTime,
)
