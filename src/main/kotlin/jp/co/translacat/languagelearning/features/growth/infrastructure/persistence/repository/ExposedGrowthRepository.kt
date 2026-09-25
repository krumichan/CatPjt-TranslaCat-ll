package jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.repository

import jp.co.translacat.languagelearning.features.growth.domain.model.*
import jp.co.translacat.languagelearning.features.growth.domain.repository.GrowthRepository
import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.table.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import java.time.LocalDate

internal class ExposedGrowthRepository(private val guard: () -> Unit) : GrowthRepository {

    private fun toGrowthProfile(row: ResultRow) = GrowthProfile(
        userId = row[GrowthProfilesTable.userId],
        profileVersion = row[GrowthProfilesTable.profileVersion],
        state = row[GrowthProfilesTable.state],
        baseLevelScore = row[GrowthProfilesTable.baseLevelScore],
        calibrationStartedDate = row[GrowthProfilesTable.calibrationStartedDate],
        calibrationCompletedDate = row[GrowthProfilesTable.calibrationCompletedDate],
        meaningScore = row[GrowthProfilesTable.meaningScore],
        grammarScore = row[GrowthProfilesTable.grammarScore],
        vocabularyScore = row[GrowthProfilesTable.vocabularyScore],
        naturalnessScore = row[GrowthProfilesTable.naturalnessScore],
        expressionScore = row[GrowthProfilesTable.expressionScore],
        reviewPerformance = row[GrowthProfilesTable.reviewPerformance],
        normalPerformance = row[GrowthProfilesTable.normalPerformance],
        challengePerformance = row[GrowthProfilesTable.challengePerformance],
        evaluationCount = row[GrowthProfilesTable.evaluationCount],
        confidence = row[GrowthProfilesTable.confidence],
        trend = row[GrowthProfilesTable.trend],
        additionalSignalsJson = row[GrowthProfilesTable.additionalSignalsJson],
        baselineCompletionId = row[GrowthProfilesTable.baselineCompletionId],
        baselineCompletedAt = row[GrowthProfilesTable.baselineCompletedAt],
        createdAt = row[GrowthProfilesTable.createdAt],
        updatedAt = row[GrowthProfilesTable.updatedAt],
    )

    private fun writeGrowthProfile(statement: UpdateBuilder<*>, value: GrowthProfile, creating: Boolean = false) {
        statement[GrowthProfilesTable.userId] = value.userId
        statement[GrowthProfilesTable.profileVersion] = value.profileVersion
        statement[GrowthProfilesTable.state] = value.state
        statement[GrowthProfilesTable.baseLevelScore] = value.baseLevelScore
        statement[GrowthProfilesTable.calibrationStartedDate] = value.calibrationStartedDate
        statement[GrowthProfilesTable.calibrationCompletedDate] = value.calibrationCompletedDate
        statement[GrowthProfilesTable.meaningScore] = value.meaningScore
        statement[GrowthProfilesTable.grammarScore] = value.grammarScore
        statement[GrowthProfilesTable.vocabularyScore] = value.vocabularyScore
        statement[GrowthProfilesTable.naturalnessScore] = value.naturalnessScore
        statement[GrowthProfilesTable.expressionScore] = value.expressionScore
        statement[GrowthProfilesTable.reviewPerformance] = value.reviewPerformance
        statement[GrowthProfilesTable.normalPerformance] = value.normalPerformance
        statement[GrowthProfilesTable.challengePerformance] = value.challengePerformance
        statement[GrowthProfilesTable.evaluationCount] = value.evaluationCount
        statement[GrowthProfilesTable.confidence] = value.confidence
        statement[GrowthProfilesTable.trend] = value.trend
        statement[GrowthProfilesTable.additionalSignalsJson] = value.additionalSignalsJson
        statement[GrowthProfilesTable.baselineCompletionId] = value.baselineCompletionId
        statement[GrowthProfilesTable.baselineCompletedAt] = value.baselineCompletedAt
        if (creating) statement[GrowthProfilesTable.createdAt] = value.createdAt
        statement[GrowthProfilesTable.updatedAt] = value.updatedAt
        if (creating) statement[GrowthProfilesTable.createdBy] = value.userId.toString()
        statement[GrowthProfilesTable.updatedBy] = value.userId.toString()
    }

    override fun profile(userId: Long): GrowthProfile? {
        guard()
        return GrowthProfilesTable.selectAll().where { (GrowthProfilesTable.userId eq userId) }.singleOrNull()?.let(::toGrowthProfile)
    }

    override fun saveProfile(value: GrowthProfile) {
        guard()
        if (profile(value.userId) == null) GrowthProfilesTable.insert { writeGrowthProfile(it, value, creating = true) }
        else check(GrowthProfilesTable.update({ (GrowthProfilesTable.userId eq value.userId) }) { writeGrowthProfile(it, value) } == 1)
    }

    private fun toKeywordMastery(row: ResultRow) = KeywordMastery(
        userId = row[KeywordMasteriesTable.userId],
        canonicalKey = row[KeywordMasteriesTable.canonicalKey],
        score = row[KeywordMasteriesTable.score],
        evaluationCount = row[KeywordMasteriesTable.evaluationCount],
        lastSelectedDate = row[KeywordMasteriesTable.lastSelectedDate],
        selectedCount = row[KeywordMasteriesTable.selectedCount],
        createdAt = row[KeywordMasteriesTable.createdAt],
        updatedAt = row[KeywordMasteriesTable.updatedAt],
    )

    private fun writeKeywordMastery(statement: UpdateBuilder<*>, value: KeywordMastery, creating: Boolean = false) {
        statement[KeywordMasteriesTable.userId] = value.userId
        statement[KeywordMasteriesTable.canonicalKey] = value.canonicalKey
        statement[KeywordMasteriesTable.score] = value.score
        statement[KeywordMasteriesTable.evaluationCount] = value.evaluationCount
        statement[KeywordMasteriesTable.lastSelectedDate] = value.lastSelectedDate
        statement[KeywordMasteriesTable.selectedCount] = value.selectedCount
        if (creating) statement[KeywordMasteriesTable.createdAt] = value.createdAt
        statement[KeywordMasteriesTable.updatedAt] = value.updatedAt
        if (creating) statement[KeywordMasteriesTable.createdBy] = value.userId.toString()
        statement[KeywordMasteriesTable.updatedBy] = value.userId.toString()
    }

    override fun mastery(userId: Long, key: String): KeywordMastery? {
        guard()
        return KeywordMasteriesTable.selectAll().where { (KeywordMasteriesTable.userId eq userId) and (KeywordMasteriesTable.canonicalKey eq key) }.singleOrNull()?.let(::toKeywordMastery)
    }

    override fun saveMastery(value: KeywordMastery) {
        guard()
        if (mastery(value.userId, value.canonicalKey) == null) KeywordMasteriesTable.insert { writeKeywordMastery(it, value, creating = true) }
        else check(KeywordMasteriesTable.update({ (KeywordMasteriesTable.userId eq value.userId) and (KeywordMasteriesTable.canonicalKey eq value.canonicalKey) }) { writeKeywordMastery(it, value) } == 1)
    }

    private fun toGrowthSignal(row: ResultRow) = GrowthSignal(
        userId = row[GrowthSignalsTable.userId],
        type = row[GrowthSignalsTable.type],
        key = row[GrowthSignalsTable.key],
        occurrenceCount = row[GrowthSignalsTable.occurrenceCount],
        lastSeenAt = row[GrowthSignalsTable.lastSeenAt],
        createdAt = row[GrowthSignalsTable.createdAt],
        updatedAt = row[GrowthSignalsTable.updatedAt],
    )

    private fun writeGrowthSignal(statement: UpdateBuilder<*>, value: GrowthSignal, creating: Boolean = false) {
        statement[GrowthSignalsTable.userId] = value.userId
        statement[GrowthSignalsTable.type] = value.type
        statement[GrowthSignalsTable.key] = value.key
        statement[GrowthSignalsTable.occurrenceCount] = value.occurrenceCount
        statement[GrowthSignalsTable.lastSeenAt] = value.lastSeenAt
        if (creating) statement[GrowthSignalsTable.createdAt] = value.createdAt
        statement[GrowthSignalsTable.updatedAt] = value.updatedAt
        if (creating) statement[GrowthSignalsTable.createdBy] = value.userId.toString()
        statement[GrowthSignalsTable.updatedBy] = value.userId.toString()
    }

    override fun signal(userId: Long, type: String, key: String): GrowthSignal? {
        guard()
        return GrowthSignalsTable.selectAll().where { (GrowthSignalsTable.userId eq userId) and (GrowthSignalsTable.type eq type) and (GrowthSignalsTable.key eq key) }.singleOrNull()?.let(::toGrowthSignal)
    }

    override fun saveSignal(value: GrowthSignal) {
        guard()
        if (signal(value.userId, value.type, value.key) == null) GrowthSignalsTable.insert { writeGrowthSignal(it, value, creating = true) }
        else check(GrowthSignalsTable.update({ (GrowthSignalsTable.userId eq value.userId) and (GrowthSignalsTable.type eq value.type) and (GrowthSignalsTable.key eq value.key) }) { writeGrowthSignal(it, value) } == 1)
    }

    private fun toGrowthEvidence(row: ResultRow) = GrowthEvidence(
        userId = row[GrowthEvidenceTable.userId],
        source = row[GrowthEvidenceTable.evidenceSource],
        metricType = row[GrowthEvidenceTable.metricType],
        patternKey = row[GrowthEvidenceTable.patternKey],
        direction = row[GrowthEvidenceTable.direction],
        evidenceCount = row[GrowthEvidenceTable.evidenceCount],
        weightedEvidence = row[GrowthEvidenceTable.weightedEvidence],
        averageConfidence = row[GrowthEvidenceTable.averageConfidence],
        recommendedFocus = row[GrowthEvidenceTable.recommendedFocus],
        lastSeenAt = row[GrowthEvidenceTable.lastSeenAt],
        createdAt = row[GrowthEvidenceTable.createdAt],
        updatedAt = row[GrowthEvidenceTable.updatedAt],
    )

    private fun writeGrowthEvidence(statement: UpdateBuilder<*>, value: GrowthEvidence, creating: Boolean = false) {
        statement[GrowthEvidenceTable.userId] = value.userId
        statement[GrowthEvidenceTable.evidenceSource] = value.source
        statement[GrowthEvidenceTable.metricType] = value.metricType
        statement[GrowthEvidenceTable.patternKey] = value.patternKey
        statement[GrowthEvidenceTable.direction] = value.direction
        statement[GrowthEvidenceTable.evidenceCount] = value.evidenceCount
        statement[GrowthEvidenceTable.weightedEvidence] = value.weightedEvidence
        statement[GrowthEvidenceTable.averageConfidence] = value.averageConfidence
        statement[GrowthEvidenceTable.recommendedFocus] = value.recommendedFocus
        statement[GrowthEvidenceTable.lastSeenAt] = value.lastSeenAt
        if (creating) statement[GrowthEvidenceTable.createdAt] = value.createdAt
        statement[GrowthEvidenceTable.updatedAt] = value.updatedAt
        if (creating) statement[GrowthEvidenceTable.createdBy] = value.userId.toString()
        statement[GrowthEvidenceTable.updatedBy] = value.userId.toString()
    }

    override fun evidence(userId: Long, source: String, pattern: String, direction: String): GrowthEvidence? {
        guard()
        return GrowthEvidenceTable.selectAll().where { (GrowthEvidenceTable.userId eq userId) and (GrowthEvidenceTable.evidenceSource eq source) and (GrowthEvidenceTable.patternKey eq pattern) and (GrowthEvidenceTable.direction eq direction) }.singleOrNull()?.let(::toGrowthEvidence)
    }

    override fun saveEvidence(value: GrowthEvidence) {
        guard()
        if (evidence(value.userId, value.source, value.patternKey, value.direction) == null) GrowthEvidenceTable.insert { writeGrowthEvidence(it, value, creating = true) }
        else check(GrowthEvidenceTable.update({ (GrowthEvidenceTable.userId eq value.userId) and (GrowthEvidenceTable.evidenceSource eq value.source) and (GrowthEvidenceTable.patternKey eq value.patternKey) and (GrowthEvidenceTable.direction eq value.direction) }) { writeGrowthEvidence(it, value) } == 1)
    }

    private fun toGrowthActivity(row: ResultRow) = GrowthActivity(
        id = row[GrowthActivitiesTable.id],
        userId = row[GrowthActivitiesTable.userId],
        source = row[GrowthActivitiesTable.activitySource],
        referenceId = row[GrowthActivitiesTable.referenceId],
        learningDate = row[GrowthActivitiesTable.learningDate],
        title = row[GrowthActivitiesTable.title],
        durationSeconds = row[GrowthActivitiesTable.durationSeconds],
        status = row[GrowthActivitiesTable.status],
        overallScore = row[GrowthActivitiesTable.overallScore],
        evaluationConfidence = row[GrowthActivitiesTable.evaluationConfidence],
        startedAt = row[GrowthActivitiesTable.startedAt],
        completedAt = row[GrowthActivitiesTable.completedAt],
        metadataJson = row[GrowthActivitiesTable.metadataJson],
        createdAt = row[GrowthActivitiesTable.createdAt],
        updatedAt = row[GrowthActivitiesTable.updatedAt],
    )

    private fun writeGrowthActivity(statement: UpdateBuilder<*>, value: GrowthActivity, creating: Boolean = false) {
        statement[GrowthActivitiesTable.userId] = value.userId
        statement[GrowthActivitiesTable.activitySource] = value.source
        statement[GrowthActivitiesTable.referenceId] = value.referenceId
        statement[GrowthActivitiesTable.learningDate] = value.learningDate
        statement[GrowthActivitiesTable.title] = value.title
        statement[GrowthActivitiesTable.durationSeconds] = value.durationSeconds
        statement[GrowthActivitiesTable.status] = value.status
        statement[GrowthActivitiesTable.overallScore] = value.overallScore
        statement[GrowthActivitiesTable.evaluationConfidence] = value.evaluationConfidence
        statement[GrowthActivitiesTable.startedAt] = value.startedAt
        statement[GrowthActivitiesTable.completedAt] = value.completedAt
        statement[GrowthActivitiesTable.metadataJson] = value.metadataJson
        if (creating) statement[GrowthActivitiesTable.createdAt] = value.createdAt
        statement[GrowthActivitiesTable.updatedAt] = value.updatedAt
        if (creating) statement[GrowthActivitiesTable.createdBy] = value.userId.toString()
        statement[GrowthActivitiesTable.updatedBy] = value.userId.toString()
    }

    override fun activity(userId: Long, source: String, referenceId: String): GrowthActivity? {
        guard()
        return GrowthActivitiesTable.selectAll().where { (GrowthActivitiesTable.userId eq userId) and (GrowthActivitiesTable.activitySource eq source) and (GrowthActivitiesTable.referenceId eq referenceId) }.singleOrNull()?.let(::toGrowthActivity)
    }

    override fun saveActivity(value: GrowthActivity): GrowthActivity {
        guard()
        val id = if (value.id == 0L) GrowthActivitiesTable.insert { writeGrowthActivity(it, value, creating = true) }[GrowthActivitiesTable.id]
        else {
            check(GrowthActivitiesTable.update({ (GrowthActivitiesTable.id eq value.id) and (GrowthActivitiesTable.userId eq value.userId) }) { writeGrowthActivity(it, value) } == 1)
            value.id
        }
        return value.copy(id = id)
    }

    private fun toGrowthMetric(row: ResultRow) = GrowthMetric(
        metricType = row[GrowthMetricsTable.metricType],
        state = row[GrowthMetricsTable.state],
        score = row[GrowthMetricsTable.score],
        confidence = row[GrowthMetricsTable.confidence],
        notEvaluableReason = row[GrowthMetricsTable.notEvaluableReason],
    )

    override fun masteries(userId: Long, keys: List<String>?, limit: Int): List<KeywordMastery> {
        guard()
        val query = KeywordMasteriesTable.selectAll().where { KeywordMasteriesTable.userId eq userId }
        if (keys != null) query.andWhere { KeywordMasteriesTable.canonicalKey inList keys }
        return query.orderBy(KeywordMasteriesTable.score to SortOrder.ASC, KeywordMasteriesTable.id to SortOrder.ASC)
            .limit(limit).map(::toKeywordMastery)
    }

    override fun signals(userId: Long, type: String, limit: Int): List<GrowthSignal> {
        guard()
        return GrowthSignalsTable.selectAll().where { (GrowthSignalsTable.userId eq userId) and (GrowthSignalsTable.type eq type) }
            .orderBy(GrowthSignalsTable.occurrenceCount to SortOrder.DESC, GrowthSignalsTable.id to SortOrder.ASC)
            .limit(limit).map(::toGrowthSignal)
    }

    override fun evidenceList(userId: Long, source: String?, limit: Int): List<GrowthEvidence> {
        guard()
        val query = GrowthEvidenceTable.selectAll().where { GrowthEvidenceTable.userId eq userId }
        if (source != null) query.andWhere { GrowthEvidenceTable.evidenceSource eq source }
        return query.orderBy(GrowthEvidenceTable.lastSeenAt to SortOrder.DESC, GrowthEvidenceTable.id to SortOrder.ASC)
            .limit(limit).map(::toGrowthEvidence)
    }

    override fun activities(userId: Long, source: String?, from: LocalDate, to: LocalDate, afterId: Long, limit: Int): List<GrowthActivity> {
        guard()
        val query = GrowthActivitiesTable.selectAll().where {
            (GrowthActivitiesTable.userId eq userId) and (GrowthActivitiesTable.learningDate greaterEq from) and
                (GrowthActivitiesTable.learningDate lessEq to) and (GrowthActivitiesTable.id greater afterId)
        }
        if (source != null) query.andWhere { GrowthActivitiesTable.activitySource eq source }
        return query.orderBy(GrowthActivitiesTable.id).limit(limit).map(::toGrowthActivity)
    }

    override fun metrics(activityId: Long): List<GrowthMetric> {
        guard()
        return GrowthMetricsTable.selectAll().where { GrowthMetricsTable.activityId eq activityId }
            .orderBy(GrowthMetricsTable.id).map(::toGrowthMetric)
    }

    override fun replaceMetrics(activityId: Long, metrics: List<GrowthMetric>) {
        guard()
        require(metrics.map { it.metricType }.distinct().size == metrics.size)
        GrowthMetricsTable.deleteWhere { GrowthMetricsTable.activityId eq activityId }
        val owner = GrowthActivitiesTable.selectAll().where { GrowthActivitiesTable.id eq activityId }.single()
        metrics.forEach { metric ->
            GrowthMetricsTable.insert {
                it[GrowthMetricsTable.activityId] = activityId
                it[metricType] = metric.metricType
                it[state] = metric.state
                it[score] = metric.score
                it[confidence] = metric.confidence
                it[notEvaluableReason] = metric.notEvaluableReason
                it[createdBy] = owner[GrowthActivitiesTable.userId].toString()
                it[updatedBy] = owner[GrowthActivitiesTable.userId].toString()
                it[createdAt] = owner[GrowthActivitiesTable.updatedAt]
                it[updatedAt] = owner[GrowthActivitiesTable.updatedAt]
            }
        }
    }
}
