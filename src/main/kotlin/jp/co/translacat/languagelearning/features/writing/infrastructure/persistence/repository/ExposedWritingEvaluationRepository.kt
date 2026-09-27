package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.repository

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingEvaluationJob
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingEvaluationStatus
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingRecentScore
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingEvaluationAssets
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingEvaluationResult
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingScoring
import jp.co.translacat.languagelearning.features.writing.domain.repository.WritingEvaluationRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDateTime
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.WritingAnswersTable as Answers
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.WritingEvaluationsTable as Evaluations

internal class ExposedWritingEvaluationRepository(private val requireTransaction: () -> Unit) :
    WritingEvaluationRepository {
    override fun recentDailyScores(userId: Long): List<WritingRecentScore> {
        requireTransaction()
        return Evaluations.selectAll().where {
            (Evaluations.userId eq userId) and (Evaluations.evaluationContext eq "DAILY") and
                (Evaluations.status eq WritingEvaluationStatus.SUCCESS.name)
        }.orderBy(Evaluations.evaluatedAt, SortOrder.DESC).limit(20).map {
            WritingRecentScore(
                it[Evaluations.overallScore], it[Evaluations.meaningScore],
                it[Evaluations.grammarScore], it[Evaluations.vocabularyScore],
                it[Evaluations.naturalnessScore], it[Evaluations.expressionScore],
            )
        }
    }

    override fun recoverable(nowUtc: LocalDateTime, limit: Int): List<WritingEvaluationJob> {
        requireTransaction()
        require(limit in 1..100)
        return Evaluations.selectAll()
            .where {
                (Evaluations.status eq WritingEvaluationStatus.PENDING.name) and
                    ((Evaluations.evaluationLeaseUntil.isNull()) or (Evaluations.evaluationLeaseUntil lessEq nowUtc))
            }
            .orderBy(Evaluations.id)
            .limit(limit)
            .map { WritingEvaluationJob(it[Evaluations.answerId], it[Evaluations.userId]) }
    }

    override fun claim(
        userId: Long, answerId: Long, token: String, nowUtc: LocalDateTime, leaseUntil: LocalDateTime,
    ): Boolean {
        requireTransaction()
        return Evaluations.update(
            {
                (Evaluations.answerId eq answerId) and (Evaluations.userId eq userId) and
                    (Evaluations.status eq WritingEvaluationStatus.PENDING.name) and
                    ((Evaluations.evaluationLeaseUntil.isNull()) or (Evaluations.evaluationLeaseUntil lessEq nowUtc))
            },
        ) {
            it[evaluationToken] = token
            it[evaluationLeaseUntil] = leaseUntil
            it[updatedAt] = nowUtc
        } == 1
    }

    override fun fail(userId: Long, answerId: Long, token: String, code: String, nowUtc: LocalDateTime): Boolean {
        requireTransaction()
        return Evaluations.update({ owned(userId, answerId, token) }) {
            it[status] = WritingEvaluationStatus.FAILED.name
            it[failureMessage] = code
            it[evaluationToken] = null
            it[evaluationLeaseUntil] = null
            it[updatedAt] = nowUtc
        } == 1
    }

    override fun succeed(
        userId: Long, answerId: Long, token: String, result: WritingEvaluationResult, nowUtc: LocalDateTime,
    ): Boolean {
        requireTransaction()
        val p = result.payload
        val s = result.scores
        return Evaluations.update({ owned(userId, answerId, token) }) {
            it[status] = WritingEvaluationStatus.SUCCESS.name
            it[overallScore] = s.overall
            it[meaningScore] = s.meaning
            it[grammarScore] = s.grammar
            it[vocabularyScore] = s.vocabulary
            it[naturalnessScore] = s.naturalness
            it[expressionScore] = s.expression
            it[strengthsJson] = p["strengths"]?.toString() ?: "[]"
            it[weaknessesJson] = p["weaknesses"]?.toString() ?: "[]"
            it[correctionsJson] = p["corrections"]?.toString() ?: "[]"
            it[recommendedAnswersJson] = p.getValue("recommendedAnswers").toString()
            it[explanationJson] = p.getValue("explanation").toString()
            it[profileSignalsJson] = p.getValue("profileSignals").toString()
            it[evaluationRubricVersion] = WritingScoring.rubricVersion
            it[scoringPolicyVersion] = WritingScoring.policyVersion
            it[promptVersion] = WritingEvaluationAssets.promptVersion
            it[evaluatedAt] = nowUtc
            it[failureMessage] = null
            it[evaluationToken] = null
            it[evaluationLeaseUntil] = null
            it[updatedAt] = nowUtc
        } == 1
    }

    override fun hasSuccessfulForItem(userId: Long, itemId: Long): Boolean {
        requireTransaction()
        val answerIds = Answers.selectAll().where { (Answers.userId eq userId) and (Answers.dailyItemId eq itemId) }
            .map { it[Answers.id] }
        if (answerIds.isEmpty()) return false
        return !Evaluations.selectAll().where {
            (Evaluations.userId eq userId) and (Evaluations.answerId inList answerIds) and
                (Evaluations.status eq WritingEvaluationStatus.SUCCESS.name)
        }.limit(1).empty()
    }

    private fun owned(userId: Long, answerId: Long, token: String): Op<Boolean> =
        (Evaluations.answerId eq answerId) and (Evaluations.userId eq userId) and
            (Evaluations.status eq WritingEvaluationStatus.PENDING.name) and (Evaluations.evaluationToken eq token)
}
