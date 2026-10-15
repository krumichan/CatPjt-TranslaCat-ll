package jp.co.translacat.languagelearning.features.webaiplan.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.application.GrowthProjector
import jp.co.translacat.languagelearning.features.growth.domain.model.*
import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.repository.ExposedGrowthRepository
import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.repository.ExposedLearnerRepository
import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.table.LearnersTable
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.table.UserSettingsTable
import jp.co.translacat.languagelearning.features.webaiplan.application.*
import jp.co.translacat.languagelearning.features.webaiplan.domain.model.*
import jp.co.translacat.languagelearning.features.webaiplan.infrastructure.persistence.table.*
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.*

internal class ExposedWebAiStore(private val transactions: JdbcTransactionRunner) : WebAiStore {
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    override suspend fun <T> write(userId: Long, block: WebAiTransaction.() -> T): T = transactions.write {
        // 같은 사용자의 다른 프로세스 요청도 DB 잠금으로 직렬화한다.
        val tx = TransactionManager.current()
        val owner = Thread.currentThread()
        var active = true
        val guard = { check(active && Thread.currentThread() === owner && TransactionManager.currentOrNull() === tx) }
        ExposedLearnerRepository(guard).ensureAndLock(userId, LocalDateTime.now(ZoneOffset.UTC)).requireActive()
        try {
            block(Scope(userId, guard))
        } finally {
            active = false
        }
    }

    override suspend fun capability(tokenHash: String): WebAiCapability? = transactions.read {
        WebAiPlansTable.selectAll().where {
            (WebAiPlansTable.tokenHash eq tokenHash) and (WebAiPlansTable.deleted eq false)
        }.singleOrNull()?.let { WebAiCapability(it[WebAiPlansTable.userId], it[WebAiPlansTable.id]) }
    }

    override suspend fun results(userId: Long): List<WebAiResult> = transactions.read {
        requireActive(userId)
        WebAiResultsTable.selectAll().where { WebAiResultsTable.userId eq userId }
            .orderBy(WebAiResultsTable.createdAt to SortOrder.DESC, WebAiResultsTable.id to SortOrder.DESC)
            .map { json.decodeFromString<WebAiResult>(it[WebAiResultsTable.payload]) }
    }

    override suspend fun result(userId: Long, resultId: String): WebAiResult? = transactions.read {
        requireActive(userId)
        WebAiResultsTable.selectAll().where {
            (WebAiResultsTable.userId eq userId) and (WebAiResultsTable.id eq resultId)
        }.singleOrNull()?.let { json.decodeFromString<WebAiResult>(it[WebAiResultsTable.payload]) }
    }

    override suspend fun recentResults(userId: Long, limit: Int): List<WebAiResult> = transactions.read {
        requireActive(userId)
        WebAiResultsTable.selectAll().where { WebAiResultsTable.userId eq userId }
            .orderBy(WebAiResultsTable.createdAt to SortOrder.DESC, WebAiResultsTable.id to SortOrder.DESC)
            .limit(limit).map { json.decodeFromString<WebAiResult>(it[WebAiResultsTable.payload]) }
    }

    private fun requireActive(userId: Long) {
        // 조회는 학습자를 생성하지 않으며 정지·삭제된 학습자의 답안 원문을 반환하지 않는다.
        ExposedLearnerRepository { check(TransactionManager.currentOrNull() != null) }.find(userId)?.requireActive()
    }

    override suspend fun pendingCandidates(now: Instant, limit: Int): List<Pair<Long, String>> = transactions.read {
        // Provider 실패는 자동 재시도하지 않는다. 미착수/프로세스 중단으로 lease가 만료된 작업만 복구한다.
        val nextId = WebAiResultsTable.id.min()
        WebAiResultsTable.join(LearnersTable, JoinType.INNER,
            onColumn = WebAiResultsTable.userId, otherColumn = LearnersTable.userId)
            .select(WebAiResultsTable.userId, nextId).where {
                (LearnersTable.status eq "ACTIVE") and
                    ((WebAiResultsTable.status eq "PENDING") or
                        ((WebAiResultsTable.status eq "VERIFYING") and (WebAiResultsTable.leaseUntil lessEq now.utc())))
            }.groupBy(WebAiResultsTable.userId).limit(limit)
            .map { it[WebAiResultsTable.userId] to checkNotNull(it[nextId]) }
    }

    private inner class Scope(private val user: Long, private val guard: () -> Unit) : WebAiTransaction {
        override fun plans(): List<WebAiPlan> {
            guard()
            return WebAiPlansTable.selectAll().where {
                (WebAiPlansTable.userId eq user) and (WebAiPlansTable.deleted eq false)
            }.orderBy(WebAiPlansTable.createdAt to SortOrder.ASC).map {
                json.decodeFromString(it[WebAiPlansTable.payload])
            }
        }

        override fun plan(id: String): WebAiPlan? {
            guard()
            return WebAiPlansTable.selectAll().where {
                (WebAiPlansTable.userId eq user) and (WebAiPlansTable.id eq id) and (WebAiPlansTable.deleted eq false)
            }.singleOrNull()?.let { json.decodeFromString(it[WebAiPlansTable.payload]) }
        }

        override fun planIdExists(id: String): Boolean {
            guard()
            return WebAiPlansTable.selectAll().where { WebAiPlansTable.id eq id }.any()
        }

        override fun savePlan(value: WebAiPlan) {
            guard()
            val now = Instant.parse(value.updatedAt).utc()
            if (plan(value.id) == null) {
                WebAiPlansTable.insert {
                    it[id] = value.id
                    it[userId] = user
                    it[revision] = value.revision
                    it[payload] = json.encodeToString(value)
                    it[tokenHash] = null
                    it[deleted] = false
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            } else {
                WebAiPlansTable.update({ (WebAiPlansTable.id eq value.id) and (WebAiPlansTable.userId eq user) }) {
                    it[revision] = value.revision
                    it[payload] = json.encodeToString(value)
                    it[updatedAt] = now
                }
            }
        }

        override fun deletePlan(id: String) {
            guard()
            WebAiPlansTable.update({ (WebAiPlansTable.id eq id) and (WebAiPlansTable.userId eq user) }) {
                it[deleted] = true
                it[tokenHash] = null
            }
        }

        override fun rotateToken(planId: String, tokenHash: String?) {
            guard()
            check(WebAiPlansTable.update({ (WebAiPlansTable.id eq planId) and (WebAiPlansTable.userId eq user) }) {
                it[WebAiPlansTable.tokenHash] = tokenHash
            } == 1)
        }

        override fun tokenMatches(planId: String, tokenHash: String): Boolean {
            guard()
            return WebAiPlansTable.selectAll().where {
                (WebAiPlansTable.id eq planId) and (WebAiPlansTable.userId eq user) and
                    (WebAiPlansTable.tokenHash eq tokenHash) and (WebAiPlansTable.deleted eq false)
            }.any()
        }

        override fun requireLanguages(originLanguage: String, learningLanguage: String) {
            guard()
            val row = UserSettingsTable.selectAll().where { UserSettingsTable.userId eq user }.singleOrNull()
            if (row == null || row[UserSettingsTable.originLanguage] != originLanguage ||
                row[UserSettingsTable.learningLanguage] != learningLanguage
            ) throw WebAiException("WEB_AI_LANGUAGE_CHANGED", "기본 학습 언어가 변경되었습니다. 새 실행을 만들어 주세요.", 409)
        }

        override fun verificationBusy(now: Instant, excludeResultId: String?): Boolean {
            guard()
            return WebAiResultsTable.selectAll().where {
                (WebAiResultsTable.userId eq user) and (WebAiResultsTable.status eq "VERIFYING") and
                    (WebAiResultsTable.leaseUntil greater now.utc())
            }.any { excludeResultId == null || it[WebAiResultsTable.id] != excludeResultId }
        }

        override fun run(id: String): WebAiRun? {
            guard()
            return WebAiRunsTable.selectAll().where {
                (WebAiRunsTable.userId eq user) and (WebAiRunsTable.id eq id)
            }.singleOrNull()?.let { json.decodeFromString(it[WebAiRunsTable.payload]) }
        }

        override fun runByKey(planId: String, executionKey: String): WebAiRun? {
            guard()
            return WebAiRunsTable.selectAll().where {
                (WebAiRunsTable.userId eq user) and (WebAiRunsTable.planId eq planId) and
                    (WebAiRunsTable.executionKey eq executionKey)
            }.singleOrNull()?.let { json.decodeFromString(it[WebAiRunsTable.payload]) }
        }

        override fun runs(limit: Int): List<WebAiRun> {
            guard()
            return WebAiRunsTable.selectAll().where { WebAiRunsTable.userId eq user }
                .orderBy(WebAiRunsTable.createdAt to SortOrder.DESC).limit(limit)
                .map { json.decodeFromString(it[WebAiRunsTable.payload]) }
        }

        override fun saveRun(value: WebAiRun) {
            guard()
            WebAiRunsTable.insert {
                it[id] = value.id
                it[planId] = value.planId
                it[userId] = user
                it[executionKey] = value.executionKey
                it[payload] = json.encodeToString(value)
                it[createdAt] = Instant.parse(value.createdAt).utc()
            }
        }

        private fun stored(row: ResultRow) = WebAiStoredResult(
            json.decodeFromString<WebAiResult>(row[WebAiResultsTable.payload]),
            row[WebAiResultsTable.submissionId], row[WebAiResultsTable.requestHash],
            row[WebAiResultsTable.leaseId], row[WebAiResultsTable.leaseUntil]?.toInstant(ZoneOffset.UTC),
            row[WebAiResultsTable.attempts],
        )

        override fun result(id: String): WebAiStoredResult? {
            guard()
            return WebAiResultsTable.selectAll().where {
                (WebAiResultsTable.userId eq user) and (WebAiResultsTable.id eq id)
            }.singleOrNull()?.let(::stored)
        }

        override fun resultBySubmission(runId: String, submissionId: String): WebAiStoredResult? {
            guard()
            return WebAiResultsTable.selectAll().where {
                (WebAiResultsTable.userId eq user) and (WebAiResultsTable.runId eq runId) and
                    (WebAiResultsTable.submissionId eq submissionId)
            }.singleOrNull()?.let(::stored)
        }

        override fun resultForItem(runId: String, itemId: String): String? {
            guard()
            check(run(runId) != null)
            return WebAiItemsTable.selectAll().where {
                (WebAiItemsTable.runId eq runId) and (WebAiItemsTable.itemId eq itemId)
            }.singleOrNull()?.get(WebAiItemsTable.resultId)
        }

        override fun reserveItems(runId: String, resultId: String, itemIds: List<String>) {
            guard()
            itemIds.forEach { item ->
                WebAiItemsTable.insert {
                    it[WebAiItemsTable.runId] = runId
                    it[itemId] = item
                    it[WebAiItemsTable.resultId] = resultId
                }
            }
        }

        override fun saveResult(value: WebAiStoredResult) {
            guard()
            val record = value.result
            if (result(record.id) == null) {
                WebAiResultsTable.insert {
                    it[id] = record.id
                    it[runId] = record.runId
                    it[userId] = user
                    it[submissionId] = value.submissionId
                    it[requestHash] = value.requestHash
                    it[status] = record.status
                    it[payload] = json.encodeToString(record)
                    it[leaseId] = value.leaseId
                    it[leaseUntil] = value.leaseUntil?.utc()
                    it[attempts] = value.attempts
                    it[createdAt] = Instant.parse(record.createdAt).utc()
                    it[updatedAt] = Instant.parse(record.updatedAt).utc()
                }
            } else {
                WebAiResultsTable.update({ (WebAiResultsTable.id eq record.id) and (WebAiResultsTable.userId eq user) }) {
                    it[status] = record.status
                    it[payload] = json.encodeToString(record)
                    it[leaseId] = value.leaseId
                    it[leaseUntil] = value.leaseUntil?.utc()
                    it[attempts] = value.attempts
                    it[updatedAt] = Instant.parse(record.updatedAt).utc()
                }
            }
        }

        override fun applyGrowth(run: WebAiRun, resultId: String, item: WebAiVerifiedItem, now: Instant) {
            guard()
            if (item.status != "VERIFIED" || item.officialScore == null) return
            val records = ExposedGrowthRepository(guard)
            val projector = GrowthProjector(records)
            val source = item.item.areaCode.substringBefore('_')
            val reference = "web-ai:$resultId:${item.item.itemId}"
            // 결과 저장과 동일 트랜잭션에서 한 번만 반영한다. 재시도는 이미 끝난 문항을 건너뛴다.
            check(records.activity(user, source, reference) == null)
            val timestamp = now.utc()
            val date = LocalDate.parse(run.learningDate)
            if (source == "WRITING" && item.writingScores.size == 5) {
                val difficulty = when (item.item.difficulty) {
                    "EASY" -> "REVIEW"
                    "HARD" -> "CHALLENGE"
                    else -> "NORMAL"
                }
                // 외부가 선언한 keywordKeys만으로 숙련도를 올리지 않는다. 문제 주제와 공식 언어 점수는 보존한다.
                projector.apply(user, GrowthChange.WritingScored(date, difficulty, item.writingScores, emptyMap(), emptyList()), timestamp)
            }
            val metadata = buildJsonObject {
                put("origin", "WEB_AI")
                put("resultId", resultId)
                put("areaCode", item.item.areaCode)
                put("learningLanguage", run.learningLanguage)
            }.toString()
            val activity = GrowthActivity(
                userId = user, source = source, referenceId = reference, learningDate = date,
                title = run.planName.take(200), durationSeconds = 0, status = "EVALUATED",
                overallScore = item.officialScore, evaluationConfidence = 1.0,
                startedAt = Instant.parse(run.createdAt).utc(), completedAt = timestamp,
                metadataJson = metadata, createdAt = timestamp, updatedAt = timestamp,
            )
            val metrics = if (source == "WRITING") item.metrics.map {
                GrowthMetric(if (it.code == "EXPRESSION") "EXPRESSIVENESS" else it.code, "EVALUATED", it.value, 1.0, null)
            } else emptyList()
            projector.apply(user, GrowthChange.ActivityRecorded(activity, metrics), timestamp)
        }
    }

    private fun Instant.utc(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)
}
