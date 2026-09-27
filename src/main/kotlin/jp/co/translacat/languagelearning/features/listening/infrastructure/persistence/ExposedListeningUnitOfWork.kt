package jp.co.translacat.languagelearning.features.listening.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.repository.ExposedGrowthRepository
import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.repository.ExposedLearnerRepository
import jp.co.translacat.languagelearning.features.listening.application.ListeningTransaction
import jp.co.translacat.languagelearning.features.listening.application.ListeningUnitOfWork
import jp.co.translacat.languagelearning.features.listening.domain.model.*
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningTaskSelectionPolicy
import jp.co.translacat.languagelearning.features.listening.infrastructure.persistence.table.*
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.repository.ExposedUserSettingsRepository
import jp.co.translacat.languagelearning.shared.diversity.ExposedGenerationFingerprintRepository
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal class ExposedListeningUnitOfWork(
    private val runner: JdbcTransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
) : ListeningUnitOfWork {
    override suspend fun <T> read(block: ListeningTransaction.() -> T): T = runner.read { scoped(null, block) }
    override suspend fun <T> write(userId: Long, block: ListeningTransaction.() -> T): T =
        runner.write { scoped(userId, block) }

    private fun <T> scoped(userId: Long?, block: ListeningTransaction.() -> T): T {
        // repository를 트랜잭션 스레드에 묶고 사용자 단위 쓰기를 직렬화한다.
        val owner = Thread.currentThread()
        val transaction = TransactionManager.current()
        var active = true
        val guard =
            { check(active && Thread.currentThread() === owner && TransactionManager.currentOrNull() === transaction) }
        val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
        if (userId != null) ExposedLearnerRepository(guard).ensureAndLock(userId, now).requireActive()
        try {
            return ListeningJdbcScope(now, guard, userId).block()
        } finally {
            active = false
        }
    }
}

private class ListeningJdbcScope(
    override val nowUtc: LocalDateTime, private val guard: () -> Unit,
    private val writeOwner: Long?,
) : ListeningTransaction {
    private val json = Json { encodeDefaults = true }
    override val growth = ExposedGrowthRepository(guard)
    override val fingerprints = ExposedGenerationFingerprintRepository(guard, "LISTENING")
    private fun write(userId: Long) {
        guard(); check(writeOwner == userId)
    }

    override fun allocateId(): Long {
        guard()
        check(writeOwner != null)
        return ListeningRecordIds.insert { }[ListeningRecordIds.id]
    }

    override fun set(userId: Long, id: Long): ListeningSetState? {
        guard()
        return ListeningSets.selectAll()
            .where { (ListeningSets.userId eq userId) and (ListeningSets.id eq id) }
            .singleOrNull()
            ?.let { json.decodeFromString<ListeningSetState>(it[ListeningSets.state]) }
    }

    override fun sets(userId: Long, date: LocalDate?): List<ListeningSetState> {
        guard()
        return ListeningSets.selectAll().where {
            (ListeningSets.userId eq userId) and
                (date?.let { ListeningSets.learningDate eq it } ?: Op.TRUE)
        }.orderBy(ListeningSets.id, SortOrder.DESC)
            .map { json.decodeFromString<ListeningSetState>(it[ListeningSets.state]) }
    }

    override fun insertSet(value: ListeningSetState) {
        write(value.userId)
        ListeningSets.insert {
            it[id] = value.id
            it[userId] = value.userId
            it[learningDate] = LocalDate.parse(value.learningDate)
            it[learningLanguage] = value.learningLanguage
            it[learningMode] = value.learningMode
            it[status] = value.status
            it[revision] = value.revision
            it[state] = json.encodeToString(value)
            it[createdAt] = nowUtc
            it[updatedAt] = nowUtc
        }
    }

    override fun updateSet(value: ListeningSetState): ListeningSetState {
        write(value.userId)
        val updated = value.copy(revision = value.revision + 1)
        check(
            ListeningSets.update(
                { (ListeningSets.id eq value.id) and (ListeningSets.userId eq value.userId) and (ListeningSets.revision eq value.revision) },
            ) {
                it[state] = json.encodeToString(updated)
                it[status] = value.status
                it[revision] = updated.revision
                it[updatedAt] = nowUtc
            } == 1,
        ) { "LISTENING_REVISION_CONFLICT" }
        return updated
    }

    override fun session(userId: Long, id: Long): ListeningSessionState? {
        guard()
        return ListeningSessions.selectAll()
            .where { (ListeningSessions.userId eq userId) and (ListeningSessions.id eq id) }
            .singleOrNull()
            ?.let { json.decodeFromString<ListeningSessionState>(it[ListeningSessions.state]) }
    }

    override fun sessions(userId: Long): List<ListeningSessionState> {
        guard()
        return ListeningSessions.selectAll()
            .where { ListeningSessions.userId eq userId }
            .orderBy(ListeningSessions.id, SortOrder.DESC)
            .map { json.decodeFromString<ListeningSessionState>(it[ListeningSessions.state]) }
    }

    override fun insertSession(value: ListeningSessionState) {
        write(value.userId)
        require(set(value.userId, value.setId) != null) { "LISTENING_SET_NOT_FOUND" }
        ListeningSessions.insert {
            it[id] = value.id
            it[userId] = value.userId
            it[setId] = value.setId
            it[idempotencyKey] = value.idempotencyKey
            it[status] = value.status
            it[revision] = value.revision
            it[state] = json.encodeToString(value)
            it[createdAt] = nowUtc
            it[updatedAt] = nowUtc
        }
    }

    override fun updateSession(value: ListeningSessionState): ListeningSessionState {
        write(value.userId)
        val updated = value.copy(revision = value.revision + 1)
        check(
            ListeningSessions.update(
                { (ListeningSessions.id eq value.id) and (ListeningSessions.userId eq value.userId) and (ListeningSessions.revision eq value.revision) },
            ) {
                it[state] = json.encodeToString(updated)
                it[status] = value.status
                it[revision] = updated.revision
                it[updatedAt] = nowUtc
            } == 1,
        ) { "LISTENING_REVISION_CONFLICT" }
        return updated
    }

    override fun rememberSelection(userId: Long, expectedRevision: LocalDateTime, tasks: List<ListeningTaskType>) {
        write(userId)
        val repository = ExposedUserSettingsRepository(guard)
        val current = checkNotNull(repository.findForUser(userId))

        // Session commit과 같은 사용자 잠금에서 반영한다. 이미 직접 수정된 설정은 덮어쓰지 않는다.
        if (current.updatedAt != expectedRevision) return
        repository.save(
            current.copy(
                defaultListeningTaskTypesJson = ListeningTaskSelectionPolicy.toCanonicalJson(tasks),
                updatedAt = maxOf(nowUtc, current.updatedAt.plusNanos(1000)),
            ),
        )
    }

    override fun enqueue(userId: Long, aggregateId: Long, type: String, key: String, payload: JsonObject): Long {
        write(userId)
        require(type in setOf("GENERATE", "TTS", "EVALUATE", "PROFILE", "EXPLANATION"))
        val existing = ListeningJobs.selectAll()
            .where { (ListeningJobs.userId eq userId) and (ListeningJobs.key eq key) }
            .singleOrNull()
        if (existing != null) {
            check(
                existing[ListeningJobs.aggregateId] == aggregateId && existing[ListeningJobs.type] == type &&
                    Json.parseToJsonElement(existing[ListeningJobs.payload]) == payload,
            ) { "LISTENING_IDEMPOTENCY_CONFLICT" }
            return existing[ListeningJobs.id]
        }
        return ListeningJobs.insert {
            it[ListeningJobs.userId] = userId
            it[ListeningJobs.aggregateId] = aggregateId
            it[ListeningJobs.type] = type
            it[ListeningJobs.key] = key
            it[ListeningJobs.payload] = payload.toString()
            it[status] = "PENDING"
            it[availableAt] = nowUtc
            it[createdAt] = nowUtc
            it[updatedAt] = nowUtc
        }[ListeningJobs.id]
    }

    override fun recoverable(limit: Int): List<ListeningJob> {
        guard()
        require(limit in 1..100)
        return ListeningJobs.selectAll().where {
            ((ListeningJobs.status eq "PENDING") and (ListeningJobs.availableAt lessEq nowUtc)) or
                ((ListeningJobs.status eq "RUNNING") and (ListeningJobs.leaseUntil lessEq nowUtc))
        }
            .orderBy(ListeningJobs.id).limit(limit).map(::job)
    }

    override fun jobForKey(userId: Long, key: String): ListeningJob? {
        guard()
        return ListeningJobs.selectAll()
            .where { (ListeningJobs.userId eq userId) and (ListeningJobs.key eq key) }
            .singleOrNull()
            ?.let(::job)
    }

    override fun hasActiveJob(userId: Long, aggregateId: Long, type: String): Boolean {
        guard()
        return ListeningJobs.selectAll().where {
            (ListeningJobs.userId eq userId) and (ListeningJobs.aggregateId eq aggregateId) and
                (ListeningJobs.type eq type) and (ListeningJobs.status inList listOf("PENDING", "RUNNING"))
        }.limit(1).any()
    }

    override fun claim(userId: Long, id: Long, token: String, leaseUntil: LocalDateTime): ListeningJob? {
        write(userId)
        require(java.util.UUID.fromString(token).toString() == token && leaseUntil > nowUtc)
        val updated = ListeningJobs.update(
            {
                (ListeningJobs.userId eq userId) and (ListeningJobs.id eq id) and
                    (((ListeningJobs.status eq "PENDING") and (ListeningJobs.availableAt lessEq nowUtc)) or
                        ((ListeningJobs.status eq "RUNNING") and (ListeningJobs.leaseUntil lessEq nowUtc)))
            },
        ) {
            it[status] = "RUNNING"
            it[attemptCount] = ListeningJobs.attemptCount + 1
            it[ListeningJobs.token] = token
            it[ListeningJobs.leaseUntil] = leaseUntil
            it[updatedAt] = nowUtc
        }
        return if (updated == 1) job(ListeningJobs.selectAll().where { ListeningJobs.id eq id }.single()) else null
    }

    override fun finish(claim: ListeningJob, errorCode: String?): Boolean {
        write(claim.userId)
        require(claim.token != null && (errorCode == null || Regex("[A-Z][A-Z0-9_]{0,79}").matches(errorCode)))
        return ListeningJobs.update(
            {
                (ListeningJobs.id eq claim.id) and (ListeningJobs.userId eq claim.userId) and
                    (ListeningJobs.status eq "RUNNING") and (ListeningJobs.token eq claim.token) and (ListeningJobs.leaseUntil greater nowUtc)
            },
        ) {
            it[status] = if (errorCode == null) "SUCCEEDED" else "FAILED"
            it[ListeningJobs.errorCode] = errorCode
            it[token] = null
            it[leaseUntil] = null
            it[updatedAt] = nowUtc
        } == 1
    }

    override fun fail(
        claim: ListeningJob, errorCode: String, retryable: Boolean, retryLimit: Int,
        retryAfter: java.time.Duration,
    ): ListeningJobFailure {
        write(claim.userId)
        require(claim.token != null && Regex("[A-Z][A-Z0-9_]{0,79}").matches(errorCode))
        require(retryLimit >= 0 && !retryAfter.isNegative)
        val retry = retryable && claim.attemptCount <= retryLimit

        // 현재 lease를 가진 실패만 다음 실행 시각을 예약한다. 오래된 응답은 상태를 바꾸지 않는다.
        val updated = ListeningJobs.update(
            {
                (ListeningJobs.id eq claim.id) and (ListeningJobs.userId eq claim.userId) and
                    (ListeningJobs.status eq "RUNNING") and (ListeningJobs.token eq claim.token) and (ListeningJobs.leaseUntil greater nowUtc)
            },
        ) {
            it[status] = if (retry) "PENDING" else "FAILED"
            it[ListeningJobs.errorCode] = errorCode
            it[token] = null
            it[leaseUntil] = null
            it[availableAt] = nowUtc.plus(retryAfter)
            it[updatedAt] = nowUtc
        }
        return when {
            updated == 0 -> ListeningJobFailure.STALE; retry -> ListeningJobFailure.RETRY; else -> ListeningJobFailure.EXHAUSTED
        }
    }

    override fun renew(claim: ListeningJob): Boolean {
        write(claim.userId)
        require(claim.token != null)
        return ListeningJobs.update(
            {
                (ListeningJobs.id eq claim.id) and (ListeningJobs.userId eq claim.userId) and
                    (ListeningJobs.status eq "RUNNING") and (ListeningJobs.token eq claim.token) and (ListeningJobs.leaseUntil greater nowUtc)
            },
        ) {
            it[leaseUntil] = nowUtc.plusMinutes(5)
            it[updatedAt] = nowUtc
        } == 1
    }

    override fun release(claim: ListeningJob) {
        write(claim.userId)
        require(claim.token != null)
        ListeningJobs.update(
            {
                (ListeningJobs.id eq claim.id) and (ListeningJobs.userId eq claim.userId) and
                    (ListeningJobs.status eq "RUNNING") and (ListeningJobs.token eq claim.token)
            },
        ) {
            it[status] = "PENDING"
            it[token] = null
            it[leaseUntil] = null
            it[availableAt] = nowUtc
            it[updatedAt] = nowUtc
        }
    }

    override fun audioUsersToExpire(limit: Int): List<Long> {
        guard()
        require(limit in 1..1000)
        return ListeningAudios.select(ListeningAudios.userId)
            .where { (ListeningAudios.retentionUntil lessEq nowUtc) and ListeningAudios.deletedAt.isNull() }
            .withDistinct()
            .limit(limit)
            .map { it[ListeningAudios.userId] }
    }

    override fun insertAudio(value: ListeningAudioState) {
        write(value.userId)
        require(value.bytes != null && value.bytes.size <= 20_000_000 && value.retentionUntil > nowUtc)
        require(
            value.checksum == java.security.MessageDigest.getInstance("SHA-256").digest(value.bytes)
                .joinToString("") { "%02x".format(it) },
        ) { "LISTENING_AUDIO_CHECKSUM_MISMATCH" }
        ListeningAudios.insert {
            it[id] = value.id
            it[userId] = value.userId
            it[ownerId] = value.ownerId
            it[revision] = value.revision
            it[contentType] = value.contentType
            it[checksum] = value.checksum
            it[bytes] = ExposedBlob(value.bytes)
            it[retentionUntil] = value.retentionUntil
            it[createdAt] = nowUtc
        }
    }

    override fun audio(userId: Long, id: Long): ListeningAudioState? {
        guard()
        return ListeningAudios.selectAll()
            .where { (ListeningAudios.userId eq userId) and (ListeningAudios.id eq id) }
            .singleOrNull()
            ?.let {
                ListeningAudioState(
                    it[ListeningAudios.id], userId, it[ListeningAudios.ownerId], it[ListeningAudios.revision],
                    it[ListeningAudios.contentType], it[ListeningAudios.checksum], it[ListeningAudios.bytes]?.bytes,
                    it[ListeningAudios.retentionUntil], it[ListeningAudios.deletedAt],
                )
            }
    }

    override fun extendAudio(userId: Long, id: Long, until: LocalDateTime) {
        write(userId)
        ListeningAudios.update(
            {
                (ListeningAudios.userId eq userId) and (ListeningAudios.id eq id) and
                    (ListeningAudios.deletedAt.isNull()) and (ListeningAudios.retentionUntil greater nowUtc) and (ListeningAudios.retentionUntil less until)
            },
        ) {
            it[retentionUntil] = until
        }
    }

    override fun expireAudio(userId: Long): Int {
        write(userId)
        return ListeningAudios.update(
            { (ListeningAudios.userId eq userId) and (ListeningAudios.retentionUntil lessEq nowUtc) and ListeningAudios.deletedAt.isNull() },
        ) {
            it[bytes] = null
            it[deletedAt] = nowUtc
        }
    }

    override fun deleteAudio(userId: Long, id: Long) {
        write(userId)
        ListeningAudios.update({ (ListeningAudios.userId eq userId) and (ListeningAudios.id eq id) }) {
            it[bytes] = null
            it[deletedAt] = nowUtc
        }
    }

    private fun job(row: ResultRow) = ListeningJob(
        row[ListeningJobs.id], row[ListeningJobs.userId], row[ListeningJobs.aggregateId],
        row[ListeningJobs.type], row[ListeningJobs.key], Json.parseToJsonElement(row[ListeningJobs.payload]).jsonObject,
        row[ListeningJobs.token], row[ListeningJobs.attemptCount], row[ListeningJobs.status],
    )

    override fun metricHistory(userId: Long, language: String): List<ListeningMetricHistoryState> {
        guard()
        return ListeningMetricHistories.selectAll()
            .where { (ListeningMetricHistories.userId eq userId) and (ListeningMetricHistories.language eq language) }
            .orderBy(ListeningMetricHistories.createdAt, SortOrder.DESC)
            .map { json.decodeFromString(it[ListeningMetricHistories.state]) }
    }

    override fun saveMetricHistory(value: ListeningMetricHistoryState) {
        write(value.userId)
        // 사용자 잠금 아래 중복 평가 근거를 한 행으로 유지하며 재계산된 가중치만 같은 ID에 반영한다.
        val existing = ListeningMetricHistories.selectAll().where {
            (ListeningMetricHistories.userId eq value.userId) and
                (ListeningMetricHistories.evaluationId eq value.referenceEvaluationId) and (ListeningMetricHistories.metric eq value.metric)
        }.singleOrNull()
        if (existing != null) {
            require(existing[ListeningMetricHistories.id] == value.id)
            ListeningMetricHistories.update({ ListeningMetricHistories.id eq value.id }) {
                it[state] = json.encodeToString(value)
            }
            return
        }
        ListeningMetricHistories.insert {
            it[id] = value.id
            it[userId] = value.userId
            it[language] = value.learningLanguage
            it[metric] = value.metric
            it[evaluationId] = value.referenceEvaluationId
            it[state] = json.encodeToString(value)
            it[createdAt] = java.time.LocalDateTime.parse(value.createdAt)
        }
    }

    override fun recommendations(userId: Long, language: String): List<ListeningRecommendationState> {
        guard()
        return ListeningRecommendations.selectAll()
            .where { (ListeningRecommendations.userId eq userId) and (ListeningRecommendations.language eq language) }
            .orderBy(ListeningRecommendations.createdAt, SortOrder.DESC)
            .map { json.decodeFromString(it[ListeningRecommendations.state]) }
    }

    override fun saveRecommendation(value: ListeningRecommendationState) {
        write(value.userId)
        val existing = ListeningRecommendations.selectAll().where {
            (ListeningRecommendations.userId eq value.userId) and
                (ListeningRecommendations.language eq value.learningLanguage) and (ListeningRecommendations.metric eq value.targetMetric) and
                (ListeningRecommendations.calculationVersion eq value.calculationVersion)
        }.singleOrNull()
        if (existing != null) {
            require(existing[ListeningRecommendations.id] == value.id)
            ListeningRecommendations.update({ ListeningRecommendations.id eq value.id }) {
                it[state] = json.encodeToString(value)
                it[updatedAt] = nowUtc
            }
            return
        }
        ListeningRecommendations.insert {
            it[id] = value.id
            it[userId] = value.userId
            it[language] = value.learningLanguage
            it[metric] = value.targetMetric
            it[calculationVersion] = value.calculationVersion
            it[state] = json.encodeToString(value)
            it[createdAt] = java.time.LocalDateTime.parse(value.createdAt)
            it[updatedAt] = nowUtc
        }
    }

    override fun recommendation(userId: Long, id: Long): ListeningRecommendationState? {
        guard()
        return ListeningRecommendations.selectAll().where {
            (ListeningRecommendations.userId eq userId) and (ListeningRecommendations.id eq id)
        }.singleOrNull()?.let { json.decodeFromString(it[ListeningRecommendations.state]) }
    }
}
