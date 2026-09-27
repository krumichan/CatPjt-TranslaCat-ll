package jp.co.translacat.languagelearning.features.writing.application

import java.time.LocalDate
import java.time.LocalDateTime

internal data class WritingReportSet(
    val id: Long, val date: LocalDate, val type: String, val count: Int,
    val status: String, val answered: Int,
)

internal data class WritingReportEvaluation(
    val setId: Long, val learningDate: LocalDate, val attemptDate: LocalDate,
    val context: String, val status: String, val evaluatedAt: LocalDateTime?, val scores: List<Int?>,
    val profileSignalsJson: String?,
)

internal data class WritingReportData(
    val sets: List<WritingReportSet>, val evaluations: List<WritingReportEvaluation>,
    val totalAnswered: Long,
)

internal fun interface WritingReportQueries {
    suspend fun read(userId: Long): WritingReportData
}
