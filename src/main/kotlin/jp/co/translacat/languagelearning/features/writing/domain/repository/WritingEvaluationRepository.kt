package jp.co.translacat.languagelearning.features.writing.domain.repository

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingEvaluationJob
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingRecentScore
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingEvaluationResult
import java.time.LocalDateTime

internal interface WritingEvaluationRepository {
    fun recentDailyScores(userId: Long): List<WritingRecentScore>
    fun recoverable(nowUtc: LocalDateTime, limit: Int): List<WritingEvaluationJob>
    fun claim(userId: Long, answerId: Long, token: String, nowUtc: LocalDateTime, leaseUntil: LocalDateTime): Boolean
    fun fail(userId: Long, answerId: Long, token: String, code: String, nowUtc: LocalDateTime): Boolean
    fun succeed(
        userId: Long, answerId: Long, token: String, result: WritingEvaluationResult, nowUtc: LocalDateTime,
    ): Boolean

    fun hasSuccessfulForItem(userId: Long, itemId: Long): Boolean
}
