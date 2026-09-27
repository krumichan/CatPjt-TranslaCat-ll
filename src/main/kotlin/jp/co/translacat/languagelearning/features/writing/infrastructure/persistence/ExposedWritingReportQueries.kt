package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.writing.application.WritingReportData
import jp.co.translacat.languagelearning.features.writing.application.WritingReportEvaluation
import jp.co.translacat.languagelearning.features.writing.application.WritingReportQueries
import jp.co.translacat.languagelearning.features.writing.application.WritingReportSet
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.WritingAnswersTable as Answers
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.WritingEvaluationsTable as Evaluations
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.WritingItemsTable as Items
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.WritingSetsTable as Sets

/** 보고서에는 답변 원문을 읽지 않고 소유 사용자에 한정한 날짜·점수·상태만 전달한다. */
internal class ExposedWritingReportQueries(private val transactions: JdbcTransactionRunner) : WritingReportQueries {
    override suspend fun read(userId: Long): WritingReportData = transactions.read {
        // 세트·문항·답변 관계는 같은 읽기 트랜잭션에서 모아 집계 기준을 고정한다.
        val sets = Sets.selectAll().where { Sets.userId eq userId }
            .orderBy(Sets.learningDate, SortOrder.DESC).toList()
        val items = Items.select(Items.id, Items.dailySetId).where { Items.userId eq userId }
            .associate { it[Items.id] to it[Items.dailySetId] }
        val answers = Answers.select(Answers.id, Answers.dailyItemId, Answers.attemptDate)
            .where { Answers.userId eq userId }.toList()
        val setDates = sets.associate { it[Sets.id] to it[Sets.learningDate] }
        val answerRows = answers.associateBy { it[Answers.id] }
        val answeredBySet = answers.mapNotNull { row ->
            items[row[Answers.dailyItemId]]?.let {
                it to row[Answers.dailyItemId]
            }
        }.distinct().groupingBy { it.first }.eachCount()

        // 최근 평가의 순서와 DAILY/REVIEW 구분을 보존하며 삭제된 관계는 오류로 드러낸다.
        val evaluations = Evaluations.selectAll().where { Evaluations.userId eq userId }
            .orderBy(Evaluations.evaluatedAt, SortOrder.DESC).map { row ->
                val answer = checkNotNull(answerRows[row[Evaluations.answerId]])
                val setId = checkNotNull(items[answer[Answers.dailyItemId]])
                WritingReportEvaluation(
                    setId, checkNotNull(setDates[setId]), answer[Answers.attemptDate],
                    row[Evaluations.evaluationContext], row[Evaluations.status], row[Evaluations.evaluatedAt],
                    listOf(
                        row[Evaluations.overallScore], row[Evaluations.meaningScore], row[Evaluations.grammarScore],
                        row[Evaluations.vocabularyScore], row[Evaluations.naturalnessScore],
                        row[Evaluations.expressionScore],
                    ),
                    row[Evaluations.profileSignalsJson],
                )
            }
        WritingReportData(
            sets.map {
                WritingReportSet(
                    it[Sets.id], it[Sets.learningDate], it[Sets.writingType],
                    it[Sets.sentenceCount], it[Sets.status], answeredBySet[it[Sets.id]] ?: 0,
                )
            },
            evaluations,
            answers.map { it[Answers.dailyItemId] }.distinct().size.toLong(),
        )
    }
}
