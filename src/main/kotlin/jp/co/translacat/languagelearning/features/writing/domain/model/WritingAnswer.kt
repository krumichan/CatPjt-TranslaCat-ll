package jp.co.translacat.languagelearning.features.writing.domain.model

import java.time.LocalDate
import java.time.LocalDateTime

internal enum class WritingEvaluationStatus { PENDING, SUCCESS, FAILED }
internal data class WritingAnswer(
    val id: Long,
    val itemId: Long,
    val userId: Long,
    val attemptDate: LocalDate,
    val text: String,
    val evaluationStatus: WritingEvaluationStatus,
)

/** 조회는 삭제된 평가 없이 남은 답안도 보존하며, 제출용 WritingAnswer의 상태 계약은 유지한다. */
internal data class WritingAnswerEvidence(
    val id: Long,
    val itemId: Long,
    val userId: Long,
    val attemptDate: LocalDate,
    val text: String,
    val evaluationStatus: WritingEvaluationStatus?,
)

internal data class WritingEvaluationView(
    val id: Long,
    val context: String,
    val overall: Int,
    val meaning: Int,
    val grammar: Int,
    val vocabulary: Int,
    val naturalness: Int,
    val expression: Int,
    val strengthsJson: String,
    val weaknessesJson: String,
    val correctionsJson: String,
    val recommendedAnswersJson: String,
    val explanationJson: String,
    val evaluationRubricVersion: String,
    val scoringPolicyVersion: String,
    val promptVersion: String,
    val evaluatedAt: LocalDateTime,
)

internal data class WritingAttemptView(
    val answer: WritingAnswerEvidence,
    val submittedAt: LocalDateTime,
    val failureMessage: String?,
    val evaluation: WritingEvaluationView?,
)
