package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.repository

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingAnswer
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingAnswerEvidence
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingAttemptView
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingEvaluationStatus
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingEvaluationView
import jp.co.translacat.languagelearning.features.writing.domain.repository.WritingAnswerRepository
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate
import java.time.LocalDateTime
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.WritingAnswersTable as Answers
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.WritingEvaluationsTable as Evaluations

internal class ExposedWritingAnswerRepository(private val requireTransaction: () -> Unit) : WritingAnswerRepository {
    override fun history(userId: Long, itemId: Long): List<WritingAttemptView> {
        requireTransaction()
        // 답변은 소유 문항으로 제한하고 날짜 순서로 읽는다.
        return Answers.selectAll().where {
            (Answers.userId eq userId) and (Answers.dailyItemId eq itemId)
        }.orderBy(Answers.attemptDate).map { answer ->
            val evaluation = Evaluations.selectAll().where {
                (Evaluations.userId eq userId) and (Evaluations.answerId eq answer[Answers.id])
            }.singleOrNull()
            // 평가 행이 없는 조회는 원문과 null 상태로 표시한다. 쓰기 경로의 필수 평가 계약은 바꾸지 않는다.
            val status = evaluation?.let { WritingEvaluationStatus.valueOf(it[Evaluations.status]) }
            val result = if (evaluation != null && status == WritingEvaluationStatus.SUCCESS) WritingEvaluationView(
                evaluation[Evaluations.id], evaluation[Evaluations.evaluationContext],
                checkNotNull(evaluation[Evaluations.overallScore]),
                checkNotNull(evaluation[Evaluations.meaningScore]),
                checkNotNull(evaluation[Evaluations.grammarScore]),
                checkNotNull(evaluation[Evaluations.vocabularyScore]),
                checkNotNull(evaluation[Evaluations.naturalnessScore]),
                checkNotNull(evaluation[Evaluations.expressionScore]),
                checkNotNull(evaluation[Evaluations.strengthsJson]),
                checkNotNull(evaluation[Evaluations.weaknessesJson]),
                checkNotNull(evaluation[Evaluations.correctionsJson]),
                checkNotNull(evaluation[Evaluations.recommendedAnswersJson]),
                checkNotNull(evaluation[Evaluations.explanationJson]),
                checkNotNull(evaluation[Evaluations.evaluationRubricVersion]),
                checkNotNull(evaluation[Evaluations.scoringPolicyVersion]),
                checkNotNull(evaluation[Evaluations.promptVersion]),
                checkNotNull(evaluation[Evaluations.evaluatedAt]),
            ) else null
            WritingAttemptView(
                WritingAnswerEvidence(
                    answer[Answers.id], itemId, userId,
                    answer[Answers.attemptDate], answer[Answers.answerText], status,
                ),
                answer[Answers.submittedAt],
                evaluation?.get(Evaluations.failureMessage) ?: if (evaluation == null) "WRITING_EVALUATION_MISSING" else null,
                result,
            )
        }
    }

    override fun find(userId: Long, itemId: Long, date: LocalDate): WritingAnswer? {
        requireTransaction()
        val answer = Answers.selectAll().where {
            (Answers.userId eq userId) and (Answers.dailyItemId eq itemId) and (Answers.attemptDate eq date)
        }.singleOrNull() ?: return null
        val evaluation = Evaluations.selectAll().where {
            (Evaluations.userId eq userId) and (Evaluations.answerId eq answer[Answers.id])
        }.singleOrNull() ?: error("WRITING_EVALUATION_MISSING")
        return WritingAnswer(
            answer[Answers.id], itemId, userId, date, answer[Answers.answerText],
            WritingEvaluationStatus.valueOf(evaluation[Evaluations.status]),
        )
    }

    override fun findById(userId: Long, answerId: Long): WritingAnswer? {
        requireTransaction()
        val answer = Answers.selectAll().where { (Answers.userId eq userId) and (Answers.id eq answerId) }
            .singleOrNull() ?: return null
        return find(userId, answer[Answers.dailyItemId], answer[Answers.attemptDate])
    }

    override fun submit(
        userId: Long, itemId: Long, date: LocalDate, text: String, nowUtc: LocalDateTime, old: WritingAnswer?,
    ): WritingAnswer {
        requireTransaction()
        val answerId = if (old == null) {
            Answers.insert {
                it[Answers.userId] = userId
                it[dailyItemId] = itemId
                it[attemptDate] = date
                it[answerText] = text
                it[submittedAt] = nowUtc
                it[createdAt] = nowUtc
                it[updatedAt] = nowUtc
            }[Answers.id]
        } else {
            check(old.evaluationStatus == WritingEvaluationStatus.FAILED)
            check(
                Answers.update({ (Answers.id eq old.id) and (Answers.userId eq userId) }) {
                    it[answerText] = text
                    it[submittedAt] = nowUtc
                    it[updatedAt] = nowUtc
                } == 1,
            )
            old.id
        }
        if (old == null) {
            Evaluations.insert {
                it[Evaluations.answerId] = answerId
                it[Evaluations.userId] = userId
                it[evaluationContext] = "DAILY"
                it[status] = WritingEvaluationStatus.PENDING.name
                it[createdAt] = nowUtc
                it[updatedAt] = nowUtc
            }
        } else {
            check(
                Evaluations.update(
                    {
                        (Evaluations.answerId eq answerId) and (Evaluations.userId eq userId) and
                            (Evaluations.status eq WritingEvaluationStatus.FAILED.name)
                    },
                ) {
                    it[status] = WritingEvaluationStatus.PENDING.name
                    it[failureMessage] = null
                    it[evaluationToken] = null
                    it[evaluationLeaseUntil] = null
                    it[updatedAt] = nowUtc
                } == 1,
            )
        }
        return checkNotNull(find(userId, itemId, date))
    }
}
