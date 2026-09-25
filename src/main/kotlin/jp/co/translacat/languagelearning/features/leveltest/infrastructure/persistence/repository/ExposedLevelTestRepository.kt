package jp.co.translacat.languagelearning.features.leveltest.infrastructure.persistence.repository

import jp.co.translacat.languagelearning.features.growth.application.ApplyLevelBaseline
import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.repository.ExposedGrowthRepository
import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelTestRules
import jp.co.translacat.languagelearning.features.leveltest.domain.repository.LevelTestRepository
import jp.co.translacat.languagelearning.features.leveltest.infrastructure.persistence.table.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.*
import java.time.LocalDateTime

internal class ExposedLevelTestRepository(private val requireTransaction: () -> Unit) : LevelTestRepository {
    override fun claimMaintenance(token: String, now: LocalDateTime, until: LocalDateTime): Boolean {
        requireTransaction()
        return LevelMaintenanceTable.update(
            { (LevelMaintenanceTable.id eq "POOL") and ((LevelMaintenanceTable.leaseUntil.isNull()) or (LevelMaintenanceTable.leaseUntil lessEq now)) },
        ) {
            it[LevelMaintenanceTable.token] = token; it[LevelMaintenanceTable.leaseUntil] = until
        } == 1
    }

    override fun ownsMaintenance(token: String, now: LocalDateTime): Boolean {
        requireTransaction()
        return LevelMaintenanceTable.selectAll()
            .where { (LevelMaintenanceTable.id eq "POOL") and (LevelMaintenanceTable.token eq token) and (LevelMaintenanceTable.leaseUntil greater now) }
            .forUpdate()
            .singleOrNull() != null
    }

    override fun releaseMaintenance(token: String): Boolean {
        requireTransaction()
        return LevelMaintenanceTable.update(
            { (LevelMaintenanceTable.id eq "POOL") and (LevelMaintenanceTable.token eq token) },
        ) {
            it[LevelMaintenanceTable.token] = null; it[LevelMaintenanceTable.leaseUntil] = null
        } == 1
    }

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

    private fun session(row: ResultRow): LevelSession = LevelSession(
        id = row[LevelSessionsTable.id],
        uid = row[LevelSessionsTable.uid],
        userId = row[LevelSessionsTable.userId],
        sessionType = LevelTestSessionType.valueOf(row[LevelSessionsTable.sessionType]),
        status = LevelTestSessionStatus.valueOf(row[LevelSessionsTable.status]),
        originLanguage = row[LevelSessionsTable.originLanguage],
        learningLanguage = row[LevelSessionsTable.learningLanguage],
        timezone = row[LevelSessionsTable.timezone],
        currentQuestionNumber = row[LevelSessionsTable.number],
        currentComplexityBand = row[LevelSessionsTable.band],
        baseLevelScore = row[LevelSessionsTable.baseScore],
        proficiencyBand = row[LevelSessionsTable.proficiencyBand],
        domainScores = json.decodeFromString(row[LevelSessionsTable.domainScores]),
        startedAt = row[LevelSessionsTable.startedAt],
        lastActivityAt = row[LevelSessionsTable.lastActivityAt],
        completedAt = row[LevelSessionsTable.completedAt],
        completedDate = row[LevelSessionsTable.completedDate],
        idempotencyKey = row[LevelSessionsTable.idempotencyKey],
        operationToken = row[LevelSessionsTable.operationToken],
        operationKind = row[LevelSessionsTable.operationKind],
        leaseUntil = row[LevelSessionsTable.leaseUntil],
    )

    private fun writeSession(statement: UpdateBuilder<*>, value: LevelSession) {
        statement[LevelSessionsTable.uid] = value.uid
        statement[LevelSessionsTable.userId] = value.userId
        statement[LevelSessionsTable.sessionType] = value.sessionType.name
        statement[LevelSessionsTable.status] = value.status.name
        statement[LevelSessionsTable.originLanguage] = value.originLanguage
        statement[LevelSessionsTable.learningLanguage] = value.learningLanguage
        statement[LevelSessionsTable.timezone] = value.timezone
        statement[LevelSessionsTable.number] = value.currentQuestionNumber
        statement[LevelSessionsTable.band] = value.currentComplexityBand
        statement[LevelSessionsTable.baseScore] = value.baseLevelScore
        statement[LevelSessionsTable.proficiencyBand] = value.proficiencyBand
        statement[LevelSessionsTable.domainScores] = json.encodeToString(value.domainScores)
        statement[LevelSessionsTable.startedAt] = value.startedAt
        statement[LevelSessionsTable.lastActivityAt] = value.lastActivityAt
        statement[LevelSessionsTable.completedAt] = value.completedAt
        statement[LevelSessionsTable.completedDate] = value.completedDate
        statement[LevelSessionsTable.idempotencyKey] = value.idempotencyKey
        statement[LevelSessionsTable.operationToken] = value.operationToken
        statement[LevelSessionsTable.operationKind] = value.operationKind
        statement[LevelSessionsTable.leaseUntil] = value.leaseUntil
    }

    private fun item(row: ResultRow): LevelItem = LevelItem(
        id = row[LevelItemsTable.id],
        sessionId = row[LevelItemsTable.sessionId],
        questionNumber = row[LevelItemsTable.number],
        data = json.decodeFromString(row[LevelItemsTable.payload]),
        status = LevelTestItemStatus.valueOf(row[LevelItemsTable.status]),
        poolQuestionId = row[LevelItemsTable.poolId],
        modelAnswerAudioKey = row[LevelItemsTable.modelAudioKey],
        createdAt = row[LevelItemsTable.createdAt],
    )

    private fun writeItem(statement: UpdateBuilder<*>, value: LevelItem) {
        statement[LevelItemsTable.sessionId] = value.sessionId
        statement[LevelItemsTable.number] = value.questionNumber
        statement[LevelItemsTable.payload] = json.encodeToString(value.data)
        statement[LevelItemsTable.status] = value.status.name
        statement[LevelItemsTable.poolId] = value.poolQuestionId
        statement[LevelItemsTable.modelAudioKey] = value.modelAnswerAudioKey
        statement[LevelItemsTable.createdAt] = value.createdAt
    }

    private fun submission(row: ResultRow): LevelSubmission = LevelSubmission(
        id = row[LevelResponsesTable.id],
        itemId = row[LevelResponsesTable.itemId],
        idempotencyKey = row[LevelResponsesTable.idempotencyKey],
        fingerprint = row[LevelResponsesTable.fingerprint],
        selectedOptionKey = row[LevelResponsesTable.optionKey],
        selectedOptionKeys = json.decodeFromString(row[LevelResponsesTable.optionKeys]),
        textAnswer = row[LevelResponsesTable.textAnswer],
        audioKey = row[LevelResponsesTable.audioKey],
        audioContentType = row[LevelResponsesTable.contentType],
        audioDurationMs = row[LevelResponsesTable.durationMs],
        audioRetentionUntil = row[LevelResponsesTable.retentionUntil],
        submittedAt = row[LevelResponsesTable.submittedAt],
        manualRetryCount = row[LevelResponsesTable.manualRetryCount],
        revision = row[LevelResponsesTable.revision],
    )

    private fun writeSubmission(statement: UpdateBuilder<*>, value: LevelSubmission) {
        statement[LevelResponsesTable.itemId] = value.itemId
        statement[LevelResponsesTable.idempotencyKey] = value.idempotencyKey
        statement[LevelResponsesTable.fingerprint] = value.fingerprint
        statement[LevelResponsesTable.optionKey] = value.selectedOptionKey
        statement[LevelResponsesTable.optionKeys] = json.encodeToString(value.selectedOptionKeys)
        statement[LevelResponsesTable.textAnswer] = value.textAnswer
        statement[LevelResponsesTable.audioKey] = value.audioKey
        statement[LevelResponsesTable.contentType] = value.audioContentType
        statement[LevelResponsesTable.durationMs] = value.audioDurationMs
        statement[LevelResponsesTable.retentionUntil] = value.audioRetentionUntil
        statement[LevelResponsesTable.submittedAt] = value.submittedAt
        statement[LevelResponsesTable.manualRetryCount] = value.manualRetryCount
        statement[LevelResponsesTable.revision] = value.revision
    }

    private fun candidate(row: ResultRow): LevelCandidate = LevelCandidate(
        id = row[LevelCandidatesTable.id],
        sessionId = row[LevelCandidatesTable.sessionId],
        questionNumber = row[LevelCandidatesTable.number],
        band = row[LevelCandidatesTable.band],
        status = row[LevelCandidatesTable.status],
        token = row[LevelCandidatesTable.token],
        leaseUntil = row[LevelCandidatesTable.leaseUntil],
        attempt = row[LevelCandidatesTable.attempt],
        poolQuestionId = row[LevelCandidatesTable.poolId],
        reason = row[LevelCandidatesTable.reason],
        createdAt = row[LevelCandidatesTable.createdAt],
    )

    private fun writeCandidate(statement: UpdateBuilder<*>, value: LevelCandidate) {
        statement[LevelCandidatesTable.sessionId] = value.sessionId
        statement[LevelCandidatesTable.number] = value.questionNumber
        statement[LevelCandidatesTable.band] = value.band
        statement[LevelCandidatesTable.status] = value.status
        statement[LevelCandidatesTable.token] = value.token
        statement[LevelCandidatesTable.leaseUntil] = value.leaseUntil
        statement[LevelCandidatesTable.attempt] = value.attempt
        statement[LevelCandidatesTable.poolId] = value.poolQuestionId
        statement[LevelCandidatesTable.reason] = value.reason
        statement[LevelCandidatesTable.createdAt] = value.createdAt
    }

    private fun pool(row: ResultRow): LevelPoolQuestion = LevelPoolQuestion(
        id = row[LevelPoolTable.id],
        originLanguage = row[LevelPoolTable.originLanguage],
        learningLanguage = row[LevelPoolTable.learningLanguage],
        data = json.decodeFromString(row[LevelPoolTable.payload]),
        active = row[LevelPoolTable.active],
        quarantineReason = row[LevelPoolTable.reason],
        createdAt = row[LevelPoolTable.createdAt],
    )

    private fun writePool(statement: UpdateBuilder<*>, value: LevelPoolQuestion) {
        statement[LevelPoolTable.originLanguage] = value.originLanguage
        statement[LevelPoolTable.learningLanguage] = value.learningLanguage
        statement[LevelPoolTable.payload] = json.encodeToString(value.data)
        statement[LevelPoolTable.active] = value.active
        statement[LevelPoolTable.reason] = value.quarantineReason
        statement[LevelPoolTable.createdAt] = value.createdAt
    }

    private fun baseline(row: ResultRow): LevelBaseline = LevelBaseline(
        userId = row[LevelBaselinesTable.userId],
        sessionId = row[LevelBaselinesTable.sessionId],
        completionId = row[LevelBaselinesTable.completionId],
        sessionType = LevelTestSessionType.valueOf(row[LevelBaselinesTable.sessionType]),
        score = row[LevelBaselinesTable.score],
        proficiencyBand = row[LevelBaselinesTable.band],
        completedDate = row[LevelBaselinesTable.completedDate],
        startedAt = row[LevelBaselinesTable.startedAt],
        completedAt = row[LevelBaselinesTable.completedAt],
    )

    private fun writeBaseline(statement: UpdateBuilder<*>, value: LevelBaseline) {
        statement[LevelBaselinesTable.userId] = value.userId
        statement[LevelBaselinesTable.sessionId] = value.sessionId
        statement[LevelBaselinesTable.completionId] = value.completionId
        statement[LevelBaselinesTable.sessionType] = value.sessionType.name
        statement[LevelBaselinesTable.score] = value.score
        statement[LevelBaselinesTable.band] = value.proficiencyBand
        statement[LevelBaselinesTable.completedDate] = value.completedDate
        statement[LevelBaselinesTable.startedAt] = value.startedAt
        statement[LevelBaselinesTable.completedAt] = value.completedAt
    }

    private fun audio(row: ResultRow): LevelAudio = LevelAudio(
        key = row[LevelAudioTable.key],
        ownerUserId = row[LevelAudioTable.ownerUserId],
        purpose = row[LevelAudioTable.purpose],
        contentType = row[LevelAudioTable.contentType],
        tokenHash = row[LevelAudioTable.tokenHash],
        uploadUntil = row[LevelAudioTable.uploadUntil],
        checksum = row[LevelAudioTable.checksum],
        sizeBytes = row[LevelAudioTable.sizeBytes],
        status = row[LevelAudioTable.status],
        retentionUntil = row[LevelAudioTable.retentionUntil],
        createdAt = row[LevelAudioTable.createdAt],
    )

    private fun writeAudio(statement: UpdateBuilder<*>, value: LevelAudio) {
        statement[LevelAudioTable.key] = value.key
        statement[LevelAudioTable.ownerUserId] = value.ownerUserId
        statement[LevelAudioTable.purpose] = value.purpose
        statement[LevelAudioTable.contentType] = value.contentType
        statement[LevelAudioTable.tokenHash] = value.tokenHash
        statement[LevelAudioTable.uploadUntil] = value.uploadUntil
        statement[LevelAudioTable.checksum] = value.checksum
        statement[LevelAudioTable.sizeBytes] = value.sizeBytes
        statement[LevelAudioTable.status] = value.status
        statement[LevelAudioTable.retentionUntil] = value.retentionUntil
        statement[LevelAudioTable.createdAt] = value.createdAt
    }

    private fun evaluation(row: ResultRow): LevelEvaluation = LevelEvaluation(
        responseId = row[LevelEvaluationsTable.responseId],
        data = json.decodeFromString(row[LevelEvaluationsTable.payload]),
        evaluatedAt = row[LevelEvaluationsTable.evaluatedAt],
    )

    private fun writeEvaluation(statement: UpdateBuilder<*>, value: LevelEvaluation) {
        statement[LevelEvaluationsTable.responseId] = value.responseId
        statement[LevelEvaluationsTable.payload] = json.encodeToString(value.data)
        statement[LevelEvaluationsTable.evaluatedAt] = value.evaluatedAt
    }

    override fun session(id: Long): LevelSession? {
        requireTransaction(); return LevelSessionsTable.selectAll()
            .where { LevelSessionsTable.id eq id }
            .singleOrNull()
            ?.let(::session)
    }

    override fun sessionByKey(userId: Long, key: String): LevelSession? {
        requireTransaction()
        return LevelSessionsTable.selectAll()
            .where { (LevelSessionsTable.userId eq userId) and (LevelSessionsTable.idempotencyKey eq key) }
            .singleOrNull()
            ?.let(::session)
    }

    override fun sessions(userId: Long): List<LevelSession> {
        requireTransaction()
        return LevelSessionsTable.selectAll()
            .where { LevelSessionsTable.userId eq userId }
            .orderBy(LevelSessionsTable.id, SortOrder.DESC)
            .map(::session)
    }

    override fun saveSession(value: LevelSession): LevelSession {
        requireTransaction()
        val id = if (value.id == 0L) LevelSessionsTable.insert { writeSession(it, value) }[LevelSessionsTable.id]
        else {
            check(
                LevelSessionsTable.update({ LevelSessionsTable.id eq value.id }) {
                    writeSession(
                        it, value,
                    )
                } == 1,
            ); value.id
        }
        return value.copy(id = id)
    }

    override fun baseline(userId: Long): LevelBaseline? {
        requireTransaction(); return LevelBaselinesTable.selectAll()
            .where { LevelBaselinesTable.userId eq userId }
            .singleOrNull()
            ?.let(::baseline)
    }

    override fun saveBaseline(value: LevelBaseline) {
        requireTransaction()
        LevelBaselinesTable.upsert { writeBaseline(it, value) }
        // V008 이후에는 완료 기준점·Profile·완료 Activity를 동일 LL 트랜잭션으로 확정한다.
        ApplyLevelBaseline(
            ExposedGrowthRepository(requireTransaction),
        ).execute(
            value.userId, value.completionId, value.score.toDouble(), value.completedDate, value.startedAt,
            value.completedAt,
        )
    }

    override fun items(sessionId: Long): List<LevelItem> {
        requireTransaction(); return LevelItemsTable.selectAll()
            .where { LevelItemsTable.sessionId eq sessionId }
            .orderBy(LevelItemsTable.number)
            .map(::item)
    }

    override fun item(id: Long): LevelItem? {
        requireTransaction(); return LevelItemsTable.selectAll()
            .where { LevelItemsTable.id eq id }
            .singleOrNull()
            ?.let(::item)
    }

    override fun itemAt(sessionId: Long, number: Int): LevelItem? {
        requireTransaction(); return LevelItemsTable.selectAll()
            .where { (LevelItemsTable.sessionId eq sessionId) and (LevelItemsTable.number eq number) }
            .singleOrNull()
            ?.let(::item)
    }

    override fun saveItem(value: LevelItem): LevelItem {
        requireTransaction()
        val id = if (value.id == 0L) LevelItemsTable.insert { writeItem(it, value) }[LevelItemsTable.id]
        else {
            check(LevelItemsTable.update({ LevelItemsTable.id eq value.id }) { writeItem(it, value) } == 1); value.id
        }
        return value.copy(id = id)
    }

    override fun deleteUnansweredItem(id: Long) {
        requireTransaction()
        check(submission(id) == null) { "답변이 있는 문항은 교체할 수 없습니다." }
        LevelItemsTable.deleteWhere { LevelItemsTable.id eq id }
    }

    override fun submission(itemId: Long): LevelSubmission? {
        requireTransaction(); return LevelResponsesTable.selectAll()
            .where { LevelResponsesTable.itemId eq itemId }
            .singleOrNull()
            ?.let(::submission)
    }

    override fun saveSubmission(value: LevelSubmission): LevelSubmission {
        requireTransaction()
        val id = if (value.id == 0L) LevelResponsesTable.insert { writeSubmission(it, value) }[LevelResponsesTable.id]
        else {
            check(
                LevelResponsesTable.update({ LevelResponsesTable.id eq value.id }) {
                    writeSubmission(
                        it, value,
                    )
                } == 1,
            ); value.id
        }
        return value.copy(id = id)
    }

    override fun evaluation(responseId: Long): LevelEvaluation? {
        requireTransaction(); return LevelEvaluationsTable.selectAll()
            .where { LevelEvaluationsTable.responseId eq responseId }
            .singleOrNull()
            ?.let(::evaluation)
    }

    override fun clearEvaluation(responseId: Long) {
        requireTransaction()
        LevelEvaluationsTable.deleteWhere { LevelEvaluationsTable.responseId eq responseId }
    }

    override fun saveEvaluation(value: LevelEvaluation) {
        requireTransaction(); LevelEvaluationsTable.upsert { writeEvaluation(it, value) }
    }

    override fun candidate(sessionId: Long, number: Int, band: Int): LevelCandidate? {
        requireTransaction()
        return LevelCandidatesTable.selectAll()
            .where { (LevelCandidatesTable.sessionId eq sessionId) and (LevelCandidatesTable.number eq number) and (LevelCandidatesTable.band eq band) }
            .singleOrNull()
            ?.let(::candidate)
    }

    override fun saveCandidate(value: LevelCandidate): LevelCandidate {
        requireTransaction()
        val id = if (value.id == 0L) LevelCandidatesTable.insert { writeCandidate(it, value) }[LevelCandidatesTable.id]
        else {
            check(
                LevelCandidatesTable.update({ LevelCandidatesTable.id eq value.id }) {
                    writeCandidate(
                        it, value,
                    )
                } == 1,
            ); value.id
        }
        return value.copy(id = id)
    }

    override fun queuedCandidates(limit: Int): List<LevelCandidate> {
        requireTransaction()
        return LevelCandidatesTable.selectAll()
            .where { LevelCandidatesTable.status inList listOf("PENDING", "GENERATING") }
            .orderBy(LevelCandidatesTable.id)
            .limit(limit)
            .map(::candidate)
    }

    override fun pool(id: Long): LevelPoolQuestion? {
        requireTransaction(); return LevelPoolTable.selectAll()
            .where { LevelPoolTable.id eq id }
            .singleOrNull()
            ?.let(::pool)
    }

    override fun poolQuestions(origin: String, learning: String): List<LevelPoolQuestion> {
        requireTransaction()
        return LevelPoolTable.selectAll()
            .where { (LevelPoolTable.originLanguage eq origin) and (LevelPoolTable.learningLanguage eq learning) }
            .orderBy(LevelPoolTable.id, SortOrder.DESC)
            .map(::pool)
    }

    override fun savePool(value: LevelPoolQuestion): LevelPoolQuestion {
        requireTransaction()
        if (value.id != 0L) {
            check(LevelPoolTable.update({ LevelPoolTable.id eq value.id }) { writePool(it, value) } == 1)
            return value
        }
        val key = LevelTestRules.sha256(
            listOf(
                value.originLanguage, value.learningLanguage, value.data.itemType.name,
                value.data.complexityBand.toString(), value.data.diversityMetadata.contentHash,
                LevelTestRules.GENERATION_POLICY, LevelTestRules.MODEL_CONFIG,
            ).joinToString("\u0000").toByteArray(Charsets.UTF_8),
        )
        // 중복 생성의 기존 payload·active 상태는 덮어쓰지 않는다.
        LevelPoolTable.upsert(onUpdate = { it[LevelPoolTable.poolKey] = key }) {
            it[LevelPoolTable.poolKey] = key
            writePool(it, value)
        }
        return LevelPoolTable.selectAll().where { LevelPoolTable.poolKey eq key }.single().let(::pool)
    }

    override fun recentItems(userId: Long, learning: String, since: LocalDateTime): List<LevelItem> {
        requireTransaction()
        val ids = LevelSessionsTable.selectAll()
            .where { (LevelSessionsTable.userId eq userId) and (LevelSessionsTable.learningLanguage eq learning) }
            .map { it[LevelSessionsTable.id] }
        if (ids.isEmpty()) return emptyList()
        return LevelItemsTable.selectAll()
            .where { (LevelItemsTable.sessionId inList ids) and (LevelItemsTable.createdAt greaterEq since) }
            .orderBy(LevelItemsTable.id, SortOrder.DESC)
            .limit(2000)
            .map(::item)
    }

    override fun audio(key: String): LevelAudio? {
        requireTransaction(); return LevelAudioTable.selectAll()
            .where { LevelAudioTable.key eq key }
            .singleOrNull()
            ?.let(::audio)
    }

    override fun saveAudio(value: LevelAudio) {
        requireTransaction(); LevelAudioTable.upsert { writeAudio(it, value) }
    }

    override fun expiredAudio(now: LocalDateTime, limit: Int): List<LevelAudio> {
        requireTransaction()
        return LevelAudioTable.selectAll().where {
            (LevelAudioTable.status neq "DELETED") and ((LevelAudioTable.retentionUntil lessEq now) or ((LevelAudioTable.status eq "RESERVED") and (LevelAudioTable.uploadUntil lessEq now)))
        }.limit(limit).map(::audio)
    }
}
