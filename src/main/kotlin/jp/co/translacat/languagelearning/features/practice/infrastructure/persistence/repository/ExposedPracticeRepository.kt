package jp.co.translacat.languagelearning.features.practice.infrastructure.persistence.repository

import jp.co.translacat.languagelearning.features.practice.application.PracticeRepository
import jp.co.translacat.languagelearning.features.practice.domain.*
import jp.co.translacat.languagelearning.features.practice.infrastructure.persistence.table.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate
import java.time.LocalDateTime

internal class ExposedPracticeRepository(private val guard: () -> Unit) : PracticeRepository {
    private val json = Json { encodeDefaults = true }

    override fun find(userId: Long, setId: Long): PracticeSet? {
        guard()
        return PracticeSets.selectAll().where { (PracticeSets.id eq setId) and (PracticeSets.userId eq userId) }
            .singleOrNull()?.let(::set)
    }

    override fun today(userId: Long, date: LocalDate, domain: PracticeDomain): List<PracticeSet> {
        guard()
        return PracticeSets.selectAll().where {
            (PracticeSets.userId eq userId) and (PracticeSets.learningDate eq date) and (PracticeSets.domain eq domain.name)
        }.orderBy(PracticeSets.id).map(::set)
    }

    override fun recentScores(userId: Long, domain: PracticeDomain, mode: String): List<Double> {
        guard()
        return PracticeSets.selectAll().where {
            (PracticeSets.userId eq userId) and (PracticeSets.domain eq domain.name) and
                (PracticeSets.mode eq mode) and (PracticeSets.status eq PracticeStatus.COMPLETED.name)
        }.orderBy(PracticeSets.learningDate to SortOrder.DESC, PracticeSets.id to SortOrder.DESC)
            .limit(5).mapNotNull { it[PracticeSets.officialScore] }
    }

    override fun range(userId: Long, from: LocalDate, to: LocalDate): List<PracticeSet> {
        guard()
        require(!to.isBefore(from))
        return PracticeSets.selectAll().where {
            (PracticeSets.userId eq userId) and (PracticeSets.learningDate greaterEq from) and (PracticeSets.learningDate lessEq to)
        }.orderBy(PracticeSets.learningDate to SortOrder.DESC, PracticeSets.id to SortOrder.DESC).map(::set)
    }

    override fun save(value: PracticeSet): PracticeSet {
        guard()
        if (value.id == 0L) {
            val id = PracticeSets.insert { writeSet(it, value) }[PracticeSets.id]
            return value.copy(id = id)
        }
        check(
            PracticeSets.update({ (PracticeSets.id eq value.id) and (PracticeSets.userId eq value.userId) }) {
                writeSet(it, value)
            } == 1,
        )
        return value
    }

    override fun recentMistakes(userId: Long, domain: PracticeDomain): List<PracticeQuestionContent> {
        guard()
        return PracticeAttempts.join(
            PracticeQuestions, JoinType.INNER, PracticeAttempts.questionId, PracticeQuestions.id,
        )
            .join(PracticeSets, JoinType.INNER, PracticeQuestions.setId, PracticeSets.id).selectAll().where {
                (PracticeSets.userId eq userId) and (PracticeSets.domain eq domain.name) and (PracticeAttempts.correct eq false)
            }.orderBy(PracticeAttempts.submittedAt to SortOrder.DESC, PracticeAttempts.id to SortOrder.DESC).limit(30)
            .map { json.decodeFromString<PracticeQuestionContent>(it[PracticeQuestions.contentJson]) }
    }

    override fun questions(setId: Long): List<PracticeQuestion> {
        guard()
        return PracticeQuestions.selectAll().where { PracticeQuestions.setId eq setId }
            .orderBy(PracticeQuestions.order).map(::question)
    }

    override fun question(userId: Long, questionId: Long): PracticeQuestion? {
        guard()
        val question =
            PracticeQuestions.selectAll().where { PracticeQuestions.id eq questionId }.singleOrNull()?.let(::question)
                ?: return null
        return if (find(userId, question.setId) != null) question else null
    }

    override fun addQuestion(setId: Long, content: PracticeQuestionContent): PracticeQuestion {
        guard()
        val id = PracticeQuestions.insert {
            it[PracticeQuestions.setId] = setId
            it[order] = content.order
            it[contentJson] = json.encodeToString(content)
        }[PracticeQuestions.id]
        return PracticeQuestion(id, setId, content)
    }

    override fun attempts(setId: Long): List<PracticeAttempt> {
        guard()
        val ids = questions(setId).map { it.id }
        if (ids.isEmpty()) return emptyList()
        return PracticeAttempts.selectAll().where { PracticeAttempts.questionId inList ids }
            .orderBy(PracticeAttempts.id).map { row ->
                PracticeAttempt(
                    row[PracticeAttempts.id], row[PracticeAttempts.questionId], row[PracticeAttempts.attemptNo],
                    json.decodeFromString(row[PracticeAttempts.answerJson]), row[PracticeAttempts.correct],
                    row[PracticeAttempts.submittedAt],
                )
            }
    }

    override fun addAttempt(value: PracticeAttempt): PracticeAttempt {
        guard()
        val id = PracticeAttempts.insert {
            it[questionId] = value.questionId
            it[attemptNo] = value.attemptNo
            it[answerJson] = json.encodeToString(value.answer)
            it[correct] = value.correct
            it[submittedAt] = value.submittedAt
        }[PracticeAttempts.id]
        return value.copy(id = id)
    }

    override fun metrics(setId: Long): List<PracticeMetric> {
        guard()
        return PracticeMetrics.selectAll()
            .where { PracticeMetrics.setId eq setId }
            .orderBy(PracticeMetrics.skillTag)
            .map {
                PracticeMetric(it[PracticeMetrics.skillTag], it[PracticeMetrics.score], it[PracticeMetrics.sampleCount])
            }
    }

    override fun saveMetrics(setId: Long, values: List<PracticeMetric>) {
        guard()
        check(metrics(setId).isEmpty()) { "PRACTICE_METRICS_ALREADY_FINALIZED" }
        values.forEach { value ->
            PracticeMetrics.insert {
                it[PracticeMetrics.setId] = setId
                it[skillTag] = value.skillTag
                it[score] = value.score
                it[sampleCount] = value.sampleCount
            }
        }
    }

    override fun masteries(userId: Long): List<VocabularyMastery> {
        guard()
        return VocabularyMasteries.selectAll().where { VocabularyMasteries.userId eq userId }
            .orderBy(VocabularyMasteries.score).map {
                VocabularyMastery(
                    it[VocabularyMasteries.userId], it[VocabularyMasteries.canonicalKey],
                    it[VocabularyMasteries.displayExpression], it[VocabularyMasteries.score],
                    it[VocabularyMasteries.evaluationCount], it[VocabularyMasteries.selectedCount],
                    it[VocabularyMasteries.lastSelectedDate],
                )
            }
    }

    override fun saveMastery(value: VocabularyMastery) {
        guard()
        val updated = VocabularyMasteries.update(
            {
                (VocabularyMasteries.userId eq value.userId) and (VocabularyMasteries.canonicalKey eq value.canonicalKey)
            },
        ) { writeMastery(it, value) }
        if (updated == 0) VocabularyMasteries.insert { writeMastery(it, value) }
    }

    override fun recoverable(now: LocalDateTime, limit: Int): List<Pair<Long, Long>> {
        guard()
        require(limit in 1..100)
        return PracticeSets.selectAll().where {
            (PracticeSets.generationStatus inList listOf("PENDING", "GENERATING")) and
                (PracticeSets.nextAttemptAt.isNull() or (PracticeSets.nextAttemptAt lessEq now)) and
                (PracticeSets.generationLeaseUntil.isNull() or (PracticeSets.generationLeaseUntil lessEq now))
        }.orderBy(PracticeSets.id).limit(limit).map { it[PracticeSets.userId] to it[PracticeSets.id] }
    }

    private fun question(row: ResultRow) = PracticeQuestion(
        row[PracticeQuestions.id], row[PracticeQuestions.setId],
        json.decodeFromString<PracticeQuestionContent>(row[PracticeQuestions.contentJson]),
    )

    private fun set(row: ResultRow) = PracticeSet(
        row[PracticeSets.id], row[PracticeSets.userId], row[PracticeSets.learningDate],
        PracticeDomain.valueOf(row[PracticeSets.domain]),
        row[PracticeSets.mode], row[PracticeSets.complexityBand], row[PracticeSets.questionCount],
        row[PracticeSets.requestJson],
        PracticeStatus.valueOf(row[PracticeSets.status]),
        PracticeGenerationStatus.valueOf(row[PracticeSets.generationStatus]),
        row[PracticeSets.generationToken], row[PracticeSets.generationLeaseUntil], row[PracticeSets.nextAttemptAt],
        row[PracticeSets.retryCount],
        row[PracticeSets.failureCode], row[PracticeSets.officialScore], row[PracticeSets.promptVersion],
        row[PracticeSets.startedAt], row[PracticeSets.completedAt],
    )

    private fun writeSet(statement: UpdateBuilder<*>, value: PracticeSet) = with(PracticeSets) {
        statement[userId] = value.userId
        statement[learningDate] = value.learningDate
        statement[domain] = value.domain.name
        statement[mode] = value.mode
        statement[complexityBand] = value.complexityBand
        statement[questionCount] = value.questionCount
        statement[requestJson] = value.requestJson
        statement[status] = value.status.name
        statement[generationStatus] = value.generationStatus.name
        statement[generationToken] = value.generationToken
        statement[generationLeaseUntil] = value.generationLeaseUntil
        statement[nextAttemptAt] = value.nextAttemptAt
        statement[retryCount] = value.retryCount
        statement[failureCode] = value.failureCode
        statement[officialScore] = value.officialScore
        statement[promptVersion] = value.promptVersion
        statement[startedAt] = value.startedAt
        statement[completedAt] = value.completedAt
    }

    private fun writeMastery(statement: UpdateBuilder<*>, value: VocabularyMastery) = with(VocabularyMasteries) {
        statement[userId] = value.userId
        statement[canonicalKey] = value.canonicalKey
        statement[displayExpression] = value.displayExpression
        statement[score] = value.score
        statement[evaluationCount] = value.evaluationCount
        statement[selectedCount] = value.selectedCount
        statement[lastSelectedDate] = value.lastSelectedDate
    }
}
