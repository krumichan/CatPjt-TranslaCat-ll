package jp.co.translacat.languagelearning.features.speaking.infrastructure.persistence.repository

import jp.co.translacat.languagelearning.features.speaking.application.SpeakingRepository
import jp.co.translacat.languagelearning.features.speaking.domain.*
import jp.co.translacat.languagelearning.features.speaking.infrastructure.persistence.table.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate
import java.time.LocalDateTime

internal class ExposedSpeakingRepository(private val guard: () -> Unit) : SpeakingRepository {
    private val json = Json { encodeDefaults = true }

    override fun topics(): List<SpeakingTopic> {
        guard()
        return SpeakingTopics.selectAll()
            .map { json.decodeFromString<SpeakingTopic>(it[SpeakingTopics.payload]).copy(id = it[SpeakingTopics.id]) }
            .sortedWith(compareBy(SpeakingTopic::sortOrder, SpeakingTopic::id))
    }

    override fun topic(id: Long): SpeakingTopic? {
        guard()
        return SpeakingTopics.selectAll().where { SpeakingTopics.id eq id }.singleOrNull()
            ?.let { json.decodeFromString<SpeakingTopic>(it[SpeakingTopics.payload]).copy(id = id) }
    }

    override fun saveTopic(value: SpeakingTopic): SpeakingTopic {
        guard()
        fun write(statement: UpdateBuilder<*>) = with(SpeakingTopics) {
            statement[topicCode] = value.topicCode
            statement[version] = value.version
            statement[payload] = json.encodeToString(value)
        }
        if (value.id == 0L) return value.copy(id = SpeakingTopics.insert { write(it) }[SpeakingTopics.id])
        check(SpeakingTopics.update({ SpeakingTopics.id eq value.id }) { write(it) } == 1)
        return value
    }

    override fun session(userId: Long, sessionId: Long): SpeakingSessionRecord? {
        guard()
        return SpeakingSessions.selectAll()
            .where { (SpeakingSessions.id eq sessionId) and (SpeakingSessions.userId eq userId) }
            .singleOrNull()
            ?.let(::sessionRow)
    }

    override fun sessionByIdempotency(userId: Long, key: String): SpeakingSessionRecord? {
        guard()
        return SpeakingSessions.selectAll()
            .where { (SpeakingSessions.userId eq userId) and (SpeakingSessions.idempotencyKey eq key) }
            .singleOrNull()
            ?.let(::sessionRow)
    }

    override fun active(userId: Long): SpeakingSessionRecord? {
        guard()
        return SpeakingSessions.selectAll().where {
            (SpeakingSessions.userId eq userId) and (SpeakingSessions.status eq SpeakingSessionStatus.IN_PROGRESS.name)
        }.orderBy(SpeakingSessions.id to SortOrder.DESC).map(::sessionRow).firstOrNull { it.openingState != "FAILED" }
    }

    override fun sessions(userId: Long, from: LocalDate, to: LocalDate): List<SpeakingSessionRecord> {
        guard()
        require(!to.isBefore(from))
        return SpeakingSessions.selectAll()
            .where {
                (SpeakingSessions.userId eq userId) and (SpeakingSessions.learningDate greaterEq from) and
                    (SpeakingSessions.learningDate lessEq to)
            }
            .orderBy(SpeakingSessions.learningDate to SortOrder.DESC, SpeakingSessions.id to SortOrder.DESC)
            .map(::sessionRow)
            .filter { it.openingReady }
    }

    override fun expirable(now: LocalDateTime, limit: Int): List<Pair<Long, Long>> {
        guard()
        require(limit in 1..100)
        // 세션별 snapshot의 TTL을 다시 판정한다. 후보 조회는 마지막 활동 순서만 결정한다.
        return SpeakingSessions.selectAll().where { SpeakingSessions.status eq SpeakingSessionStatus.IN_PROGRESS.name }
            .orderBy(SpeakingSessions.lastActivityAt).map(::sessionRow).filter { it.openingReady }.take(limit)
            .filter { it.expireIfNeeded(now) != it }.map { it.userId to it.id }
    }

    override fun saveSession(value: SpeakingSessionRecord): SpeakingSessionRecord {
        guard()
        if (value.id == 0L) return value.copy(
            id = SpeakingSessions.insert { writeSession(it, value) }[SpeakingSessions.id],
        )
        check(
            SpeakingSessions.update(
                { (SpeakingSessions.id eq value.id) and (SpeakingSessions.userId eq value.userId) },
            ) {
                writeSession(it, value)
            } == 1,
        )
        return value
    }

    override fun turns(sessionId: Long): List<SpeakingTurnRecord> {
        guard()
        return SpeakingTurns.selectAll()
            .where { SpeakingTurns.sessionId eq sessionId }
            .orderBy(SpeakingTurns.turnIndex)
            .map(::turnRow)
    }

    override fun turn(sessionId: Long, turnId: Long): SpeakingTurnRecord? {
        guard()
        return SpeakingTurns.selectAll()
            .where { (SpeakingTurns.id eq turnId) and (SpeakingTurns.sessionId eq sessionId) }
            .singleOrNull()
            ?.let(::turnRow)
    }

    override fun saveTurn(value: SpeakingTurnRecord): SpeakingTurnRecord {
        guard()
        if (value.id == 0L) return value.copy(id = SpeakingTurns.insert { writeTurn(it, value) }[SpeakingTurns.id])
        check(
            SpeakingTurns.update({ (SpeakingTurns.id eq value.id) and (SpeakingTurns.sessionId eq value.sessionId) }) {
                writeTurn(it, value)
            } == 1,
        )
        return value
    }

    override fun expiredTurns(now: LocalDateTime, limit: Int): List<Triple<Long, Long, Long>> {
        guard()
        require(limit in 1..100)
        return SpeakingTurns.join(SpeakingSessions, JoinType.INNER, SpeakingTurns.sessionId, SpeakingSessions.id)
            .selectAll()
            .where {
                SpeakingTurns.executionToken.isNotNull() and (SpeakingTurns.executionLeaseUntil lessEq now)
            }
            .orderBy(SpeakingTurns.executionLeaseUntil)
            .limit(limit)
            .map {
                Triple(it[SpeakingSessions.userId], it[SpeakingSessions.id], it[SpeakingTurns.id])
            }
    }

    override fun job(sessionId: Long, problemIndex: Int): SpeakingJobRecord? {
        guard()
        return SpeakingJobs.selectAll()
            .where { (SpeakingJobs.sessionId eq sessionId) and (SpeakingJobs.problemIndex eq problemIndex) }
            .singleOrNull()
            ?.let(::jobRow)
    }

    override fun saveJob(value: SpeakingJobRecord): SpeakingJobRecord {
        guard()
        if (value.id == 0L) return value.copy(id = SpeakingJobs.insert { writeJob(it, value) }[SpeakingJobs.id])
        check(
            SpeakingJobs.update({ (SpeakingJobs.id eq value.id) and (SpeakingJobs.sessionId eq value.sessionId) }) {
                writeJob(
                    it, value,
                )
            } == 1,
        )
        return value
    }

    override fun dueJobs(now: LocalDateTime, limit: Int): List<Triple<Long, Long, Int>> {
        guard()
        require(limit in 1..100)

        // 신규 대기와 만료된 RUNNING 후보를 함께 읽는다. 실제 회수 가능 여부와 token 교체는 상태 계층에서 판정한다.
        return SpeakingJobs.join(SpeakingSessions, JoinType.INNER, SpeakingJobs.sessionId, SpeakingSessions.id)
            .selectAll()
            .where {
                (SpeakingJobs.status inList listOf("PENDING", "RUNNING")) and (SpeakingJobs.availableAt lessEq now)
            }
            .orderBy(SpeakingJobs.availableAt to SortOrder.ASC, SpeakingJobs.id to SortOrder.ASC)
            .limit(limit)
            .map { Triple(it[SpeakingSessions.userId], it[SpeakingJobs.sessionId], it[SpeakingJobs.problemIndex]) }
    }

    override fun result(sessionId: Long, problemIndex: Int): SpeakingResultRecord? {
        guard()
        return SpeakingResults.selectAll().where {
            (SpeakingResults.sessionId eq sessionId) and (SpeakingResults.problemIndex eq problemIndex)
        }.singleOrNull()?.let(::resultRow)
    }

    override fun results(sessionId: Long): List<SpeakingResultRecord> {
        guard()
        return SpeakingResults.selectAll()
            .where { SpeakingResults.sessionId eq sessionId }
            .orderBy(SpeakingResults.problemIndex)
            .map(::resultRow)
    }

    override fun saveResult(value: SpeakingResultRecord) {
        guard()
        fun write(statement: UpdateBuilder<*>) = with(SpeakingResults) {
            statement[sessionId] = value.sessionId
            statement[problemIndex] = value.problemIndex
            statement[resultKind] = value.resultKind.name
            statement[status] = value.status
            statement[response] = value.response.toString()
            statement[updatedAt] = value.updatedAt
        }

        // 사용자 트랜잭션 안에서 세션·문항의 기존 결과를 갱신하고 첫 결과만 새로 만든다.
        val count = SpeakingResults.update(
            {
                (SpeakingResults.sessionId eq value.sessionId) and (SpeakingResults.problemIndex eq value.problemIndex)
            },
        ) { write(it) }
        if (count == 0) SpeakingResults.insert { write(it) }
    }

    override fun audio(sessionId: Long, turnId: Long?, role: String): SpeakingAudioRecord? {
        guard()
        return SpeakingAudio.selectAll()
            .where {
                (SpeakingAudio.sessionId eq sessionId) and (SpeakingAudio.turnId eq turnId) and
                    (SpeakingAudio.role eq role)
            }
            .orderBy(SpeakingAudio.id to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.let(::audioRow)
            ?.takeIf { it.deletedAt == null }
    }

    override fun audioById(userId: Long, audioId: Long): SpeakingAudioRecord? {
        guard()
        return SpeakingAudio.join(SpeakingSessions, JoinType.INNER, SpeakingAudio.sessionId, SpeakingSessions.id)
            .selectAll().where { (SpeakingAudio.id eq audioId) and (SpeakingSessions.userId eq userId) }
            .singleOrNull()?.let(::audioRow)
    }

    override fun audioOwner(audioId: Long): Long? {
        guard()
        return SpeakingAudio.join(SpeakingSessions, JoinType.INNER, SpeakingAudio.sessionId, SpeakingSessions.id)
            .selectAll().where { SpeakingAudio.id eq audioId }.singleOrNull()?.get(SpeakingSessions.userId)
    }

    override fun saveAudio(value: SpeakingAudioRecord): SpeakingAudioRecord {
        guard()
        fun write(statement: UpdateBuilder<*>) = with(SpeakingAudio) {
            statement[sessionId] = value.sessionId
            statement[turnId] = value.turnId
            statement[role] = value.role
            statement[revision] = value.recordingRevision
            statement[objectKey] = value.objectKey
            statement[contentType] = value.contentType
            statement[fileName] = value.fileName
            statement[byteLength] = value.byteLength
            statement[sha256] = value.sha256
            statement[retentionUntil] = value.retentionUntil
            statement[deletedAt] = value.deletedAt
            statement[physicalDeletedAt] = value.physicalDeletedAt
            statement[deleteClaimToken] = value.deleteClaimToken
            statement[deleteLeaseUntil] = value.deleteLeaseUntil
        }
        if (value.id == 0L) return value.copy(id = SpeakingAudio.insert { write(it) }[SpeakingAudio.id])
        check(SpeakingAudio.update({ SpeakingAudio.id eq value.id }) { write(it) } == 1)
        return value
    }

    override fun dueAudio(now: LocalDateTime, limit: Int, pendingOnly: Boolean): List<SpeakingAudioRecord> {
        guard()
        require(limit in 1..100)

        // 복구 루프는 이미 선택한 삭제만 읽으며 일일 배치에서만 새 보존 만료 음성을 후보에 포함한다.
        return SpeakingAudio.selectAll().where {
            SpeakingAudio.physicalDeletedAt.isNull() and
                (if (pendingOnly) SpeakingAudio.deletedAt.isNotNull()
                else (SpeakingAudio.retentionUntil lessEq now) or SpeakingAudio.deletedAt.isNotNull()) and
                (SpeakingAudio.deleteLeaseUntil.isNull() or (SpeakingAudio.deleteLeaseUntil lessEq now))
        }
            .orderBy(SpeakingAudio.retentionUntil).limit(limit).map(::audioRow)
    }

    override fun addUsage(
        sessionId: Long, turnId: Long?, usage: JsonObject, manualRetryAttempt: Int, now: LocalDateTime,
    ) {
        guard()
        SpeakingUsage.insert {
            it[SpeakingUsage.sessionId] = sessionId
            it[SpeakingUsage.turnId] = turnId
            it[payload] = usage.toString()
            it[SpeakingUsage.manualRetryAttempt] = manualRetryAttempt
            it[createdAt] = now
        }
    }

    override fun report(userId: Long, reference: String): SpeakingSttReportRecord? {
        guard()
        return SpeakingSttReports.selectAll()
            .where { (SpeakingSttReports.userId eq userId) and (SpeakingSttReports.reference eq reference) }
            .singleOrNull()
            ?.let(::reportRow)
    }

    override fun saveReport(value: SpeakingSttReportRecord): SpeakingSttReportRecord {
        guard()
        fun write(statement: UpdateBuilder<*>) = with(SpeakingSttReports) {
            statement[userId] = value.userId
            statement[sessionId] = value.sessionId
            statement[turnId] = value.turnId
            statement[reference] = value.reference
            statement[reportType] = value.reportType
            statement[status] = value.status
            statement[expectedText] = value.expectedText
            statement[consent] = value.audioAnalysisConsent
            statement[retentionUntil] = value.audioRetentionUntil
            statement[sttMetadata] = value.sttMetadata.toString()
            statement[clientMetadata] = value.clientMetadata.toString()
            statement[supportRequested] = value.supportRequested
            statement[supportReference] = value.supportReference
            statement[createdAt] = value.createdAt
            statement[resolvedAt] = value.resolvedAt
        }
        if (value.id == 0L) return value.copy(id = SpeakingSttReports.insert { write(it) }[SpeakingSttReports.id])
        check(
            SpeakingSttReports.update(
                { (SpeakingSttReports.id eq value.id) and (SpeakingSttReports.userId eq value.userId) },
            ) {
                write(
                    it,
                )
            } == 1,
        )
        return value
    }

    override fun reportById(userId: Long, reportId: Long): SpeakingSttReportRecord? {
        guard()
        return SpeakingSttReports.selectAll()
            .where { (SpeakingSttReports.userId eq userId) and (SpeakingSttReports.id eq reportId) }
            .singleOrNull()
            ?.let(::reportRow)
    }

    private fun sessionRow(row: ResultRow) = with(SpeakingSessions) {
        SpeakingSessionRecord(
            row[id], row[userId], row[idempotencyKey], row[learningDate], json.decodeFromString(row[snapshot]),
            SpeakingSessionStatus.valueOf(row[status]), SpeakingEvaluationStatus.valueOf(row[evaluationStatus]),
            row[completedTurns], row[durationSeconds], json.decodeFromString(row[opening]), row[summary],
            json.decodeFromString(row[usage]),
            row[evaluationVersion], row[startedAt], row[completedAt], row[lastActivityAt],
        )
    }

    private fun turnRow(row: ResultRow) = with(SpeakingTurns) {
        SpeakingTurnRecord(
            row[id], row[sessionId], row[turnIndex], row[idempotencyKey], row[problemIndex], row[attemptIndex],
            row[recordingRevision], SpeakingTurnStatus.valueOf(row[status]), row[uploadToken], row[uploadExpiresAt],
            json.decodeFromString(row[content]), row[excluded], row[failedStage], row[errorCode], row[errorMessage],
            row[manualRetryCount], row[completedAt], row[executionToken], row[executionLeaseUntil],
        )
    }

    private fun jobRow(row: ResultRow) = with(SpeakingJobs) {
        SpeakingJobRecord(
            row[id], row[sessionId], row[problemIndex], SpeakingResultKind.valueOf(row[resultKind]),
            row[policyVersion], row[snapshotHash], json.decodeFromString(row[request]),
            SpeakingJobStatus.valueOf(row[status]),
            row[claimToken], row[availableAt], row[manualRetryCount], row[recoveryCount], row[lastError],
        )
    }

    private fun resultRow(row: ResultRow) = with(SpeakingResults) {
        SpeakingResultRecord(
            row[sessionId], row[problemIndex], SpeakingResultKind.valueOf(row[resultKind]),
            row[status], json.decodeFromString(row[response]), row[updatedAt], row[id],
        )
    }

    private fun audioRow(row: ResultRow) = with(SpeakingAudio) {
        SpeakingAudioRecord(
            row[id], row[sessionId], row[turnId], row[role], row[revision], row[objectKey], row[contentType],
            row[fileName], row[byteLength], row[sha256], row[retentionUntil], row[deletedAt],
            row[physicalDeletedAt], row[deleteClaimToken], row[deleteLeaseUntil],
        )
    }

    private fun reportRow(row: ResultRow) = with(SpeakingSttReports) {
        SpeakingSttReportRecord(
            row[id], row[userId], row[sessionId], row[turnId], row[reference], row[reportType], row[status],
            row[expectedText], row[consent], row[retentionUntil], json.decodeFromString(row[sttMetadata]),
            json.decodeFromString(row[clientMetadata]), row[supportRequested], row[supportReference], row[createdAt],
            row[resolvedAt],
        )
    }

    private fun writeSession(statement: UpdateBuilder<*>, value: SpeakingSessionRecord) = with(SpeakingSessions) {
        statement[userId] = value.userId
        statement[idempotencyKey] = value.createIdempotencyKey
        statement[learningDate] = value.learningDate
        statement[snapshot] = json.encodeToString(value.snapshot)
        statement[status] = value.status.name
        statement[evaluationStatus] = value.evaluationStatus.name
        statement[completedTurns] = value.completedTurns
        statement[durationSeconds] = value.totalDurationSeconds
        statement[opening] = value.opening.toString()
        statement[summary] = value.sessionSummary
        statement[usage] = value.usageSummary.toString()
        statement[evaluationVersion] = value.evaluationVersion
        statement[startedAt] = value.startedAt
        statement[completedAt] = value.completedAt
        statement[lastActivityAt] = value.lastActivityAt
    }

    private fun writeTurn(statement: UpdateBuilder<*>, value: SpeakingTurnRecord) = with(SpeakingTurns) {
        statement[sessionId] = value.sessionId
        statement[turnIndex] = value.turnIndex
        statement[idempotencyKey] = value.idempotencyKey
        statement[problemIndex] = value.problemIndex
        statement[attemptIndex] = value.attemptIndex
        statement[recordingRevision] = value.recordingRevision
        statement[status] = value.status.name
        statement[uploadToken] = value.uploadToken
        statement[uploadExpiresAt] = value.uploadExpiresAt
        statement[content] = json.encodeToString(value.content)
        statement[excluded] = value.excludedFromEvaluation
        statement[failedStage] = value.failedStage
        statement[errorCode] = value.errorCode
        statement[errorMessage] = value.errorMessage
        statement[manualRetryCount] = value.manualRetryCount
        statement[completedAt] = value.completedAt
        statement[executionToken] = value.executionToken
        statement[executionLeaseUntil] = value.executionLeaseUntil
    }

    private fun writeJob(statement: UpdateBuilder<*>, value: SpeakingJobRecord) = with(SpeakingJobs) {
        statement[sessionId] = value.sessionId
        statement[problemIndex] = value.problemIndex
        statement[resultKind] = value.resultKind.name
        statement[policyVersion] = value.resultPolicyVersion
        statement[snapshotHash] = value.sourceSnapshotHash
        statement[request] = value.request.toString()
        statement[status] = value.status.name
        statement[claimToken] = value.claimToken
        statement[availableAt] = value.availableAt
        statement[manualRetryCount] = value.manualRetryCount
        statement[recoveryCount] = value.recoveryCount
        statement[lastError] = value.lastError
    }
}
