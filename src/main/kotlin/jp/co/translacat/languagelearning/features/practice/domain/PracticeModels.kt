package jp.co.translacat.languagelearning.features.practice.domain

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime

@Serializable
internal enum class PracticeDomain { READING, VOCABULARY }
@Serializable
internal enum class PracticeDifficulty { EASIER, CURRENT, CHALLENGE }
@Serializable
internal enum class PracticeGenerationStatus { PENDING, GENERATING, READY, PARTIAL, FAILED }
@Serializable
internal enum class PracticeStatus { ACTIVE, COMPLETED }
@Serializable
internal enum class PracticeQuestionType { SINGLE_CHOICE, ORDERING }

@Serializable
internal data class PracticeOption(val key: String, val text: String)

@Serializable
internal data class PracticeQuestionContent(
    val order: Int,
    val questionType: PracticeQuestionType,
    val difficulty: PracticeDifficulty,
    val complexityBand: Int,
    val passageId: String? = null,
    val passageText: String? = null,
    val prompt: String,
    val options: List<PracticeOption>,
    val correctAnswer: List<String>,
    val skillTag: String,
    val evidenceText: String? = null,
    val explanationOrigin: String,
    val explanationLearning: String,
    val targetExpression: String? = null,
    val canonicalKey: String? = null,
    val reviewTarget: Boolean = false,
    val vocabularyCandidates: List<String> = emptyList(),
)

internal data class PracticeSet(
    val id: Long,
    val userId: Long,
    val learningDate: LocalDate,
    val domain: PracticeDomain,
    val mode: String,
    val complexityBand: Int,
    val questionCount: Int,
    val requestJson: String,
    val status: PracticeStatus = PracticeStatus.ACTIVE,
    val generationStatus: PracticeGenerationStatus = PracticeGenerationStatus.PENDING,
    val generationToken: String? = null,
    val generationLeaseUntil: LocalDateTime? = null,
    val nextAttemptAt: LocalDateTime? = null,
    val retryCount: Int = 0,
    val failureCode: String? = null,
    val officialScore: Double? = null,
    val promptVersion: String? = null,
    val startedAt: LocalDateTime,
    val completedAt: LocalDateTime? = null,
)

internal data class PracticeQuestion(val id: Long, val setId: Long, val content: PracticeQuestionContent)
internal data class PracticeAttempt(
    val id: Long,
    val questionId: Long,
    val attemptNo: Int,
    val answer: List<String>,
    val correct: Boolean,
    val submittedAt: LocalDateTime,
) {
    val official: Boolean get() = attemptNo == 1
}

internal data class PracticeMetric(val skillTag: String, val score: Double, val sampleCount: Int)

internal data class VocabularyMastery(
    val userId: Long,
    val canonicalKey: String,
    val displayExpression: String,
    val score: Double = 50.0,
    val evaluationCount: Int = 0,
    val selectedCount: Int = 0,
    val lastSelectedDate: LocalDate? = null,
) {
    val stage: String
        get() = when {
            evaluationCount == 0 -> "NEW"
            score < 55 -> "LEARNING"
            score < 70 -> "FAMILIAR"
            score < 85 -> "STRONG"
            else -> "MASTERED"
        }

    fun selected(date: LocalDate) = copy(selectedCount = selectedCount + 1, lastSelectedDate = date)

    fun evaluated(correct: Boolean) = copy(
        score = PracticePolicy.round(score * 0.65 + (if (correct) 100 else 0) * 0.35),
        evaluationCount = evaluationCount + 1,
    )
}

internal class PracticeFailure(val code: String, val status: Int = 400, val policyReason: String? = null) :
    RuntimeException(code)
