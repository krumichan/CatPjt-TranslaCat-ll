package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingDifficulty
import jp.co.translacat.languagelearning.features.growth.domain.policy.GrowthLanguageScope
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.*
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.*
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 별도 opt-in 계획. 기존 학습 준비 활동을 보존하며 모델·공식 점수·능력 평가를 추가하지 않는다. */
internal class CuratedWritingPlanStore(
    private val transactions: JdbcTransactionRunner,
    private val snapshots: CuratedWritingStore,
    private val releaseId: String,
    val limits: CuratedWritingPlanLimits = CuratedWritingPlanLimits(),
    private val clock: Clock = Clock.systemUTC(),
    private val feedback: CuratedWritingFeedbackWorker? = null,
) {
    data class SupplyClaim(val userId: Long, val setId: Long, val planRevision: Int, val token: String)

    fun config(qaOnly: Boolean): JsonObject = buildJsonObject {
        put("policyVersion", CuratedWritingPlanPolicy.policyVersion)
        put("defaultTargetItemCount", 5.coerceAtMost(limits.maxTargetItemCount))
        put("minTargetItemCount", 1)
        put("maxTargetItemCount", limits.maxTargetItemCount)
        put("workerBatchSize", limits.workerBatchSize)
        put("pageSize", limits.pageSize)
        put("answerMaxLength", 10000)
        put("qaOnly", qaOnly)
        put("personalizedFeedbackEnabled", feedback != null)
        put("distribution", counts(mapOf(WritingDifficulty.REVIEW to 20, WritingDifficulty.NORMAL to 60,
            WritingDifficulty.CHALLENGE to 20)))
    }

    suspend fun preview(request: CuratedWritingStore.Start, target: Int): JsonObject = transactions.read {
        limits.requireTarget(target)
        val band = baseBand(request.userId)
        val slots = CuratedWritingPlanPolicy.slots(target, band)
        val selected = selection(request, slots, emptyList())
        buildJsonObject {
            put("policyVersion", CuratedWritingPlanPolicy.policyVersion)
            put("resultPolicy", "REFERENCE_ONLY")
            put("writingType", request.type.name)
            put("baseBand", band)
            measures(target, slots, selected.slots.map { it.first }).forEach { (key, value) -> put(key, value) }
            put("canStart", selected.slots.size == target)
            put("requiresPartialConsent", selected.slots.size != target)
            put("rePractice", request.rePractice)
            put("qaOnly", request.qaOnly)
        }
    }

    suspend fun start(request: CuratedWritingStore.Start, target: Int, allowPartial: Boolean): JsonObject =
        transactions.write {
            limits.requireTarget(target)
            require(request.userId > 0 && request.reviewDays > 0)
            val now = nowUtc()
            ExposedWritingSetUnitOfWork.lockCurrentOwner(request.userId, now)

            // 날짜·유형을 선점한 정책과 언어를 바꾸지 않는다. 재전송도 목표 변경을 암묵 수행하지 않는다.
            val existing = snapshots.findSet(request.userId, request.date, request.type)
            if (existing != null) {
                requirePlan(existing, request.originLanguage, request.learningLanguage)
                require(existing[CuratedSetsTable.targetItemCount] == target) { "WRITING_TARGET_CONFLICT" }
                return@write render(existing, request.userId, request.date, request.reviewDays)
            }
            require(WritingSetsTable.selectAll().where {
                (WritingSetsTable.userId eq request.userId) and (WritingSetsTable.learningDate eq request.date) and
                    (WritingSetsTable.writingType eq request.type.name) and
                    ((WritingSetsTable.languageScope eq GrowthLanguageScope.key(request.originLanguage, request.learningLanguage)) or
                        (WritingSetsTable.languageScope eq ""))
            }.empty()) { "WRITING_POLICY_CONFLICT" }
            val band = baseBand(request.userId)
            val slots = CuratedWritingPlanPolicy.slots(target, band)
            val selected = selection(request, slots, emptyList())
            require(allowPartial || selected.slots.size == target) { "WRITING_CONTENT_NOT_AVAILABLE" }

            // 부분 동의가 있으면 확보한 문항과 미확보 슬롯을 함께 저장한다. 빈 슬롯은 가짜 문항이 아니다.
            val id = CuratedSetsTable.insert {
                it[userId] = request.userId
                it[learningDate] = request.date
                it[writingType] = request.type.name
                it[policyVersion] = CuratedWritingPlanPolicy.policyVersion
                it[resultPolicy] = "REFERENCE_ONLY"
                it[CuratedSetsTable.releaseId] = this@CuratedWritingPlanStore.releaseId
                it[originLanguage] = request.originLanguage
                it[learningLanguage] = request.learningLanguage
                it[baseBand] = band
                it[status] = supplyStatus(target, selected.slots.size)
                it[rePractice] = request.rePractice
                it[replacementCount] = 0
                it[targetItemCount] = target
                it[planRevision] = 1
                it[plannedSlotsJson] = encodeSlots(slots)
                it[createdAt] = now
                it[updatedAt] = now
            }[CuratedSetsTable.id]
            selected.slots.forEach { (slot, item) -> snapshots.insertItem(request.userId, id, slot, item, now,
                CuratedWritingPlanPolicy.policyVersion) }
            if (selected.slots.isNotEmpty()) {
                ExposedWritingSetUnitOfWork.markLearningPrepared(request.userId, request.date, now)
            }
            render(checkNotNull(snapshots.findSetById(request.userId, id)), request.userId,
                request.date, request.reviewDays)
        }

    suspend fun byId(userId: Long, id: Long, today: LocalDate, reviewDays: Int): JsonObject? = transactions.read {
        CuratedWritingActivity.requireActive(userId)
        snapshots.findSetById(userId, id)?.let {
            requirePlan(it)
            render(it, userId, today, reviewDays)
        }
    }

    suspend fun byDate(userId: Long, date: LocalDate, type: WritingType, today: LocalDate, reviewDays: Int): JsonObject? =
        transactions.read {
            CuratedWritingActivity.requireActive(userId)
            snapshots.findSet(userId, date, type)?.let {
                requirePlan(it)
                render(it, userId, today, reviewDays)
            }
        }

    suspend fun expand(request: CuratedWritingStore.Start, id: Long, target: Int, revision: Int): JsonObject =
        transactions.write {
            limits.requireTarget(target)
            ExposedWritingSetUnitOfWork.lockCurrentOwner(request.userId, nowUtc())
            val set = owned(request, id, revision, checkRevision = false)
            require(target >= set[CuratedSetsTable.targetItemCount]) { "WRITING_TARGET_SHRINK_NOT_ALLOWED" }
            if (target == set[CuratedSetsTable.targetItemCount] && revision in 1..set[CuratedSetsTable.planRevision]) {
                return@write render(set, request.userId, request.date, request.reviewDays)
            }
            require(set[CuratedSetsTable.planRevision] == revision) { "WRITING_PLAN_STALE" }
            require(revision < Int.MAX_VALUE) { "WRITING_PLAN_STALE" }
            val slots = CuratedWritingPlanPolicy.slots(target, set[CuratedSetsTable.baseBand], decodeSlots(set))
            val entries = snapshots.setItems(request.userId, id)
            val selected = selection(request.copy(rePractice = set[CuratedSetsTable.rePractice],
                type = WritingType.valueOf(set[CuratedSetsTable.writingType])),
                slots.filter { slot -> entries.none { it[CuratedItemsTable.itemOrder] == slot.order } }, entries)

            // 기존 행·답안·revision은 그대로 두고 새 슬롯만 채운다. 구 revision의 늦은 공급은 무효화한다.
            selected.slots.forEach { (slot, item) -> snapshots.insertItem(request.userId, id, slot, item, nowUtc(),
                CuratedWritingPlanPolicy.policyVersion) }
            CuratedSetsTable.update({ CuratedSetsTable.id eq id }) {
                it[targetItemCount] = target
                it[planRevision] = revision + 1
                it[plannedSlotsJson] = encodeSlots(slots)
                it[status] = supplyStatus(target, entries.size + selected.slots.size)
                it[supplyToken] = null
                it[supplyLeaseUntil] = null
                it[completedSupplyToken] = null
                it[supplyStopReason] = null
                it[updatedAt] = nowUtc()
            }
            if (selected.slots.isNotEmpty()) {
                ExposedWritingSetUnitOfWork.markLearningPrepared(request.userId, request.date, nowUtc())
            }
            val saved = checkNotNull(snapshots.findSetById(request.userId, id))
            CuratedWritingActivity.synchronize(saved, nowUtc())
            render(saved, request.userId, request.date, request.reviewDays)
        }

    suspend fun claimSupply(request: CuratedWritingStore.Start, id: Long, revision: Int): SupplyClaim =
        transactions.write {
            val now = nowUtc()
            ExposedWritingSetUnitOfWork.lockCurrentOwner(request.userId, now)
            val set = owned(request, id, revision)
            require(set[CuratedSetsTable.supplyLeaseUntil]?.isAfter(now) != true) { "WRITING_SUPPLY_BUSY" }
            val token = UUID.randomUUID().toString()
            CuratedSetsTable.update({ CuratedSetsTable.id eq id }) {
                it[supplyToken] = token
                it[supplyLeaseUntil] = now.plusSeconds(limits.leaseSeconds)
                it[supplyStopReason] = null
                it[updatedAt] = now
            }
            SupplyClaim(request.userId, id, revision, token)
        }

    suspend fun completeSupply(request: CuratedWritingStore.Start, claim: SupplyClaim): JsonObject =
        transactions.write {
            require(claim.userId == request.userId) { "WRITING_SET_NOT_FOUND" }
            val now = nowUtc()
            ExposedWritingSetUnitOfWork.lockCurrentOwner(request.userId, now)
            val set = owned(request, claim.setId, claim.planRevision)
            if (set[CuratedSetsTable.completedSupplyToken] == claim.token) {
                return@write render(set, request.userId, request.date, request.reviewDays)
            }
            requireClaim(set, claim, now)
            val entries = snapshots.setItems(request.userId, claim.setId)
            val missing = decodeSlots(set).filter { slot ->
                entries.none { it[CuratedItemsTable.itemOrder] == slot.order }
            }
            // 재고가 없는 앞 슬롯이 뒤의 공급 가능 슬롯을 막지 않도록 전체 부족분을 먼저 매칭한다.
            val available = selection(request.copy(rePractice = set[CuratedSetsTable.rePractice],
                type = WritingType.valueOf(set[CuratedSetsTable.writingType])), missing, entries)
            val selected = CuratedSelection(available.slots.take(limits.workerBatchSize))

            // 토큰·목표 revision·lease를 확인한 뒤에만 현재 재고를 복사한다. 실패한 worker의 결과는 쓰지 않는다.
            selected.slots.forEach { (slot, item) -> snapshots.insertItem(request.userId, claim.setId, slot, item, now,
                CuratedWritingPlanPolicy.policyVersion) }
            val answers = snapshots.setAnswers(request.userId, entries.map { it[CuratedItemsTable.id] })
            val count = entries.size + selected.slots.size
            val status = if (count == set[CuratedSetsTable.targetItemCount] &&
                selected.slots.isEmpty() && answers.size == count) "COMPLETED"
            else supplyStatus(set[CuratedSetsTable.targetItemCount], count)
            CuratedSetsTable.update({ CuratedSetsTable.id eq claim.setId }) {
                it[CuratedSetsTable.status] = status
                it[supplyToken] = null
                it[supplyLeaseUntil] = null
                it[completedSupplyToken] = claim.token
                it[updatedAt] = now
            }
            if (selected.slots.isNotEmpty()) {
                ExposedWritingSetUnitOfWork.markLearningPrepared(request.userId, request.date, now)
            }
            val saved = checkNotNull(snapshots.findSetById(request.userId, claim.setId))
            CuratedWritingActivity.synchronize(saved, now)
            render(saved, request.userId, request.date, request.reviewDays)
        }

    suspend fun stopSupply(request: CuratedWritingStore.Start, claim: SupplyClaim, reason: String) = transactions.write {
        require(reason in setOf("CANCELLED", "DEADLINE", "BUDGET_EXHAUSTED"))
        require(claim.userId == request.userId) { "WRITING_SET_NOT_FOUND" }
        val now = nowUtc()
        ExposedWritingSetUnitOfWork.lockCurrentOwner(request.userId, now)
        val set = owned(request, claim.setId, claim.planRevision)
        requireClaim(set, claim, now)
        CuratedSetsTable.update({ CuratedSetsTable.id eq claim.setId }) {
            it[supplyToken] = null
            it[supplyLeaseUntil] = null
            it[supplyStopReason] = reason
            it[updatedAt] = now
        }
    }

    suspend fun restore(request: CuratedWritingStore.Start, id: Long, revision: Int): JsonObject =
        completeSupply(request, claimSupply(request, id, revision))

    suspend fun replacePrepared(request: CuratedWritingStore.Start, itemId: Long, requestKey: String,
                                contentRevision: String, planRevision: Int): JsonObject = transactions.write {
        // 준비와 적용을 분리한다. 이 명령은 저장한 소유 후보만 사용하며 모델을 호출하지 않는다.
        require(request.qaOnly) { "WRITING_POLICY_CONFLICT" }
        require(requestKey.matches(Regex("[a-zA-Z0-9._-]{1,80}"))) { "WRITING_REQUEST_INVALID" }
        val now = nowUtc()
        ExposedWritingSetUnitOfWork.lockCurrentOwner(request.userId, now)
        val item = snapshots.findItem(request.userId, itemId) ?: error("WRITING_ITEM_NOT_FOUND")
        val set = owned(request, item[CuratedItemsTable.setId], planRevision, checkRevision = false)
        val replay = CuratedWritingReplacementTable.selectAll().where {
            (CuratedWritingReplacementTable.userId eq request.userId) and
                (CuratedWritingReplacementTable.requestKey eq requestKey)
        }.singleOrNull()
        if (replay != null) {
            require(replay[CuratedWritingReplacementTable.itemId] == itemId &&
                replay[CuratedWritingReplacementTable.originalRevision] == contentRevision &&
                replay[CuratedWritingReplacementTable.originalPlanRevision] == planRevision &&
                replay[CuratedWritingReplacementTable.replacementRevision] == item[CuratedItemsTable.contentRevision]) {
                "WRITING_ITEM_STALE"
            }
            return@write render(set, request.userId, request.date, request.reviewDays)
        }
        require(set[CuratedSetsTable.planRevision] == planRevision && planRevision < Int.MAX_VALUE) { "WRITING_PLAN_STALE" }
        require(item[CuratedItemsTable.contentRevision] == contentRevision) { "WRITING_ITEM_STALE" }
        require(set[CuratedSetsTable.supplyLeaseUntil]?.isAfter(now) != true) { "WRITING_SUPPLY_BUSY" }
        require(snapshots.setAnswers(request.userId, listOf(itemId)).isEmpty()) { "WRITING_ANSWER_NOT_ALLOWED" }

        // 원본과 새 후보의 소유권·연결·언어·band를 검증한다. 임의 후보나 다른 문항의 repair는 적용하지 않는다.
        val prepared = CuratedGenerationTable.selectAll().where {
            (CuratedGenerationTable.userId eq request.userId) and (CuratedGenerationTable.requestKey eq requestKey) and
                (CuratedGenerationTable.status eq "SUCCEEDED")
        }.singleOrNull() ?: error("WRITING_PREPARATION_NOT_FOUND")
        val input = Json.parseToJsonElement(prepared[CuratedGenerationTable.requestJson]).jsonObject
        val originalKey = input["repairOfRequestKey"]?.jsonPrimitive?.content ?: error("WRITING_PREPARATION_CONFLICT")
        val original = CuratedGenerationTable.selectAll().where {
            (CuratedGenerationTable.userId eq request.userId) and (CuratedGenerationTable.requestKey eq originalKey)
        }.singleOrNull() ?: error("WRITING_PREPARATION_NOT_FOUND")
        require(original[CuratedGenerationTable.catalogId] == item[CuratedItemsTable.catalogId]) { "WRITING_PREPARATION_CONFLICT" }
        val candidate = snapshots.eligible(request.originLanguage, request.learningLanguage,
            WritingType.valueOf(set[CuratedSetsTable.writingType]), request.userId)
            .singleOrNull { it.id == prepared[CuratedGenerationTable.catalogId] } ?: error("WRITING_CONTENT_NOT_AVAILABLE")
        require(candidate.releaseId == releaseId && candidate.band == item[CuratedItemsTable.targetBand]) { "WRITING_PREPARATION_CONFLICT" }
        require(snapshots.setItems(request.userId, set[CuratedSetsTable.id]).none {
            it[CuratedItemsTable.id] != itemId && it[CuratedItemsTable.semanticKey] == candidate.semanticKey
        }) { "WRITING_CONTENT_NOT_AVAILABLE" }
        val nextRevision = CuratedWritingManifestCodec.sha256("${candidate.contentHash}|$itemId|$requestKey")

        // 기존 본문·정답·식별 정보를 먼저 보존하고 같은 트랜잭션에서 revision을 바꾼다.
        val archive = buildJsonObject {
            put("catalogId", item[CuratedItemsTable.catalogId]); put("catalogVersion", item[CuratedItemsTable.catalogVersion])
            put("contentHash", item[CuratedItemsTable.contentHash]); put("semanticKey", item[CuratedItemsTable.semanticKey])
            put("public", Json.parseToJsonElement(item[CuratedItemsTable.publicJson]))
            put("private", Json.parseToJsonElement(item[CuratedItemsTable.privateJson]))
            put("itemPolicyVersion", item[CuratedItemsTable.itemPolicyVersion]); put("createdAt", item[CuratedItemsTable.createdAt].toString())
        }
        CuratedWritingReplacementTable.insert {
            it[userId] = request.userId; it[CuratedWritingReplacementTable.itemId] = itemId
            it[CuratedWritingReplacementTable.requestKey] = requestKey; it[originalRevision] = contentRevision
            it[replacementRevision] = nextRevision; it[originalPlanRevision] = planRevision
            it[originalSnapshotJson] = archive.toString(); it[createdAt] = now
        }
        CuratedItemsTable.update({ (CuratedItemsTable.id eq itemId) and (CuratedItemsTable.userId eq request.userId) }) {
            it[catalogId] = candidate.id; it[catalogVersion] = candidate.version; it[contentHash] = candidate.contentHash
            it[semanticKey] = candidate.semanticKey; it[publicJson] = snapshots.publicContent(candidate).toString()
            it[privateJson] = snapshots.privateContent(candidate).toString(); it[CuratedItemsTable.contentRevision] = nextRevision
            it[updatedAt] = now
        }
        CuratedSetsTable.update({ CuratedSetsTable.id eq set[CuratedSetsTable.id] }) {
            it[CuratedSetsTable.planRevision] = planRevision + 1
            it[replacementCount] = set[CuratedSetsTable.replacementCount] + 1
            it[completedSupplyToken] = null; it[updatedAt] = now
        }
        render(checkNotNull(snapshots.findSetById(request.userId, set[CuratedSetsTable.id])),
            request.userId, request.date, request.reviewDays)
    }

    suspend fun delete(request: CuratedWritingStore.Start, id: Long, revision: Int): JsonObject = transactions.write {
        ExposedWritingSetUnitOfWork.lockCurrentOwner(request.userId, nowUtc())
        val set = snapshots.findSetById(request.userId, id) ?: error("WRITING_SET_NOT_FOUND")
        requirePlan(set)
        require(set[CuratedSetsTable.planRevision] == revision) { "WRITING_PLAN_STALE" }

        // 학습자는 자신의 계획만 삭제한다. FK의 자식부터 같은 트랜잭션에서 제거해 늦은 결과가 붙을 대상을 없앤다.
        val itemIds = snapshots.setItems(request.userId, id).map { it[CuratedItemsTable.id] }
        CuratedWritingFeedbackTable.deleteWhere {
            (CuratedWritingFeedbackTable.userId eq request.userId) and (CuratedWritingFeedbackTable.setId eq id)
        }
        if (itemIds.isNotEmpty()) {
            CuratedAnswersTable.deleteWhere {
                (CuratedAnswersTable.userId eq request.userId) and (CuratedAnswersTable.itemId inList itemIds)
            }
        }
        CuratedItemsTable.deleteWhere {
            (CuratedItemsTable.userId eq request.userId) and (CuratedItemsTable.setId eq id)
        }
        CuratedWritingActivity.delete(request.userId, id)
        CuratedSetsTable.deleteWhere { (CuratedSetsTable.userId eq request.userId) and (CuratedSetsTable.id eq id) }
        buildJsonObject {
            put("dailySetId", LearningPublicId.encode(id))
            put("deleted", true)
        }
    }

    suspend fun submit(request: CuratedWritingStore.Start, itemId: Long, answer: String, revision: String): JsonObject {
        // 저장 언어와 설정을 대조해 설정 변경 후 다른 언어 세션에 쓰는 것을 막는다.
        val id = transactions.read {
            val item = snapshots.findItem(request.userId, itemId) ?: error("WRITING_ITEM_NOT_FOUND")
            val set = snapshots.findSetById(request.userId, item[CuratedItemsTable.setId]) ?: error("WRITING_SET_NOT_FOUND")
            requirePlan(set, request.originLanguage, request.learningLanguage)
            set[CuratedSetsTable.id]
        }
        snapshots.submit(request.userId, itemId, answer, revision, request.date, request.reviewDays,
            CuratedWritingPlanPolicy.policyVersion)
        // 답안 저장이 끝난 뒤에만 비점수 작업을 실행한다. 조회와 재전송은 호출을 만들지 않는다.
        feedback?.submitted(request, itemId)
        return checkNotNull(byId(request.userId, id, request.date, request.reviewDays))
    }

    suspend fun retryFeedback(request: CuratedWritingStore.Start, answerId: Long): JsonObject {
        val worker = feedback ?: error("WRITING_FEEDBACK_DISABLED")
        worker.retry(request, answerId)
        val setId = transactions.read {
            val answer = CuratedAnswersTable.selectAll().where {
                (CuratedAnswersTable.id eq answerId) and (CuratedAnswersTable.userId eq request.userId)
            }.singleOrNull() ?: error("WRITING_ANSWER_NOT_FOUND")
            val item = snapshots.findItem(request.userId, answer[CuratedAnswersTable.itemId])
                ?: error("WRITING_ITEM_NOT_FOUND")
            item[CuratedItemsTable.setId]
        }
        return checkNotNull(byId(request.userId, setId, request.date, request.reviewDays))
    }

    private fun owned(request: CuratedWritingStore.Start, id: Long, revision: Int, checkRevision: Boolean = true): ResultRow {
        val set = snapshots.findSetById(request.userId, id) ?: error("WRITING_SET_NOT_FOUND")
        requirePlan(set, request.originLanguage, request.learningLanguage)
        require(set[CuratedSetsTable.releaseId] == releaseId) { "WRITING_RELEASE_CONFLICT" }
        require(!checkRevision || set[CuratedSetsTable.planRevision] == revision) { "WRITING_PLAN_STALE" }
        require(request.reviewDays > 0 && !request.date.isBefore(set[CuratedSetsTable.learningDate]) &&
            !request.date.isAfter(set[CuratedSetsTable.learningDate].plusDays(request.reviewDays - 1L))) {
            "WRITING_REVIEW_EXPIRED"
        }
        return set
    }

    private fun requirePlan(set: ResultRow, origin: String? = null, learning: String? = null) {
        require(set[CuratedSetsTable.policyVersion] == CuratedWritingPlanPolicy.policyVersion) { "WRITING_POLICY_CONFLICT" }
        require((origin == null || set[CuratedSetsTable.originLanguage] == origin) &&
            (learning == null || set[CuratedSetsTable.learningLanguage] == learning)) { "WRITING_LANGUAGE_CONFLICT" }
    }

    private fun requireClaim(set: ResultRow, claim: SupplyClaim, now: LocalDateTime) {
        require(set[CuratedSetsTable.supplyToken] == claim.token &&
            set[CuratedSetsTable.supplyLeaseUntil]?.isAfter(now) == true) { "WRITING_SUPPLY_STALE" }
    }

    private fun selection(request: CuratedWritingStore.Start, slots: List<CuratedSlot>, entries: List<ResultRow>): CuratedSelection {
        val seen = CuratedItemsTable.selectAll().where { CuratedItemsTable.userId eq request.userId }.toList()
        val seenIds = seen.map { it[CuratedItemsTable.catalogId] }.toSet()
        val seenKeys = seen.map { it[CuratedItemsTable.semanticKey] }.toSet()
        val retainedIds = entries.map { it[CuratedItemsTable.catalogId] }.toSet()
        val retainedKeys = entries.map { it[CuratedItemsTable.semanticKey] }.toSet()
        val candidates = snapshots.eligible(request.originLanguage, request.learningLanguage, request.type, request.userId)
            .filter { request.candidateIds.isEmpty() || (request.qaOnly && it.id in request.candidateIds) }.filter {
            it.releaseId == releaseId && it.id !in retainedIds && it.semanticKey !in retainedKeys &&
                (request.rePractice || it.id !in seenIds && it.semanticKey !in seenKeys) &&
                (it.status == CuratedReviewStatus.APPROVED || request.qaOnly &&
                    it.status in setOf(CuratedReviewStatus.DRAFT, CuratedReviewStatus.AUTO_REVIEWED))
        }
        return CuratedWritingPlanPolicy.select(slots, candidates, "${request.userId}|${request.date}|${request.type}")
    }

    private fun render(set: ResultRow, userId: Long, today: LocalDate, reviewDays: Int): JsonObject {
        val original = snapshots.renderSet(set, userId, today, reviewDays)
        val entries = original.getValue("items").jsonArray
        val actual = entries.map { it.jsonObject }.map {
            CuratedSlot(it.getValue("order").jsonPrimitive.int,
                WritingDifficulty.valueOf(it.getValue("difficulty").jsonPrimitive.content),
                it.getValue("targetBand").jsonPrimitive.int)
        }
        val enriched = if (feedback == null) entries else {
            val answerIds = entries.flatMap { entry ->
                entry.jsonObject.getValue("attempts").jsonArray.map { attempt ->
                    LearningPublicId.decode(attempt.jsonObject.getValue("answerId").jsonPrimitive.content)
                }
            }
            val states = feedback.byAnswerIds(userId, answerIds)
            entries.map { entry ->
                val item = entry.jsonObject
                JsonObject(item + ("attempts" to JsonArray(item.getValue("attempts").jsonArray.map { attempt ->
                    val answer = attempt.jsonObject
                    val answerId = LearningPublicId.decode(answer.getValue("answerId").jsonPrimitive.content)
                    JsonObject(answer + ("feedback" to feedback.publicState(states[answerId])))
                })))
            }
        }
        val feedbackCompleted = enriched.count { entry ->
            entry.jsonObject.getValue("attempts").jsonArray.lastOrNull()?.jsonObject
                ?.get("feedback")?.jsonObject?.get("status")?.jsonPrimitive?.content == "SUCCEEDED"
        }
        // 준비 요청은 소유자가 일치하는 저장 후보에만 연결한다. 복원 화면에서 추가 생성하지 않는다.
        val preparationKeys = CuratedGenerationTable.selectAll().where {
            (CuratedGenerationTable.userId eq userId) and (CuratedGenerationTable.status eq "SUCCEEDED")
        }.associate { it[CuratedGenerationTable.catalogId] to it[CuratedGenerationTable.requestKey] }
        return JsonObject(original + measures(set[CuratedSetsTable.targetItemCount], decodeSlots(set), actual) + mapOf(
            "planRevision" to JsonPrimitive(set[CuratedSetsTable.planRevision]),
            "activity" to CuratedWritingActivity.publicState(userId, set[CuratedSetsTable.id]),
            "submittedItemCount" to JsonPrimitive(entries.count { it.jsonObject.getValue("answered").jsonPrimitive.boolean }),
            "feedbackCompletedItemCount" to JsonPrimitive(feedbackCompleted),
            "personalizedFeedbackStatus" to JsonPrimitive(if (feedback == null) "DISABLED_PENDING_QUALITY_GATE" else "ACTIVE_QA"),
            "supplyStopReason" to (set[CuratedSetsTable.supplyStopReason]?.let(::JsonPrimitive) ?: JsonNull),
            "items" to JsonArray(enriched.map { entry ->
                val item = entry.jsonObject
                JsonObject(item + mapOf("preparationRequestKey" to
                    (preparationKeys[item.getValue("catalogId").jsonPrimitive.content]?.let(::JsonPrimitive) ?: JsonNull),
                    "workload" to buildJsonObject {
                    put("sourceCharacterCount", item.getValue("originText").jsonPrimitive.content.length)
                    put("estimatedAnswerCharacters", JsonNull)
                    put("estimateStatus", "NOT_SPECIFIED")
                }))
            }),
        ))
    }

    private fun measures(target: Int, planned: List<CuratedSlot>, actual: List<CuratedSlot>): JsonObject {
        val desired = CuratedWritingPlanPolicy.distribution(target)
        val acquired = WritingDifficulty.entries.associateWith { kind -> actual.count { it.difficulty == kind } }
        return buildJsonObject {
            put("targetItemCount", target)
            put("acquiredItemCount", actual.size)
            put("remainingItemCount", target - actual.size)
            put("desiredDistribution", counts(desired))
            put("actualDistribution", counts(acquired))
            put("distributionDeviation", counts(desired.mapValues { (key, value) -> acquired.getValue(key) - value }))
            put("shortages", JsonArray(planned.filter { slot -> actual.none { it.order == slot.order } }
                .groupBy { it.difficulty to it.targetBand }.map { (key, slots) -> buildJsonObject {
                    put("difficulty", key.first.name)
                    put("targetBand", key.second)
                    put("missingCount", slots.size)
                } }))
        }
    }

    private fun counts(values: Map<WritingDifficulty, Int>) = buildJsonObject {
        WritingDifficulty.entries.forEach { put(it.name, values.getValue(it)) }
    }
    private fun encodeSlots(slots: List<CuratedSlot>) = JsonArray(slots.map { slot -> buildJsonObject {
        put("order", slot.order)
        put("difficulty", slot.difficulty.name)
        put("targetBand", slot.targetBand)
    } }).toString()
    private fun decodeSlots(set: ResultRow) = Json.parseToJsonElement(checkNotNull(set[CuratedSetsTable.plannedSlotsJson]))
        .jsonArray.map { it.jsonObject }.map { CuratedSlot(it.getValue("order").jsonPrimitive.int,
            WritingDifficulty.valueOf(it.getValue("difficulty").jsonPrimitive.content),
            it.getValue("targetBand").jsonPrimitive.int) }
    private fun supplyStatus(target: Int, acquired: Int) = when {
        acquired == target -> "READY"
        acquired == 0 -> "BLOCKED"
        else -> "PARTIAL"
    }
    private fun baseBand(userId: Long): Int {
        val profile = ExposedWritingSetUnitOfWork.currentGrowthProfile(userId)
        require(profile != null && profile.state != "LEVEL_TEST_REQUIRED") { "LEVEL_TEST_REQUIRED" }
        return when (val score = profile.baseLevelScore) {
            null -> 2
            else -> when {
                score < 40 -> 1
                score < 55 -> 2
                score < 70 -> 3
                score < 85 -> 4
                else -> 5
            }
        }
    }
    private fun nowUtc(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
}
