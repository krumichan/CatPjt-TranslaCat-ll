package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingDifficulty
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

/** 신규 비점수 세션의 짧은 DB 트랜잭션. 기존 평가·점수 테이블에는 쓰지 않는다. */
internal class CuratedWritingStore(
    private val transactions: JdbcTransactionRunner,
    private val manifest: CuratedWritingManifest,
    private val clock: Clock = Clock.systemUTC(),
    private val approvals: Map<Pair<String, Int>, CuratedWritingApproval> = emptyMap(),
    private val approvalManifestHash: String? = null,
) {
    private val catalog = manifest.items.associateBy { it.id to it.version }

    data class Start(
        val userId: Long,
        val date: LocalDate,
        val type: WritingType,
        val originLanguage: String,
        val learningLanguage: String,
        val rePractice: Boolean,
        val qaOnly: Boolean,
        val reviewDays: Int,
    )

    /** 로컬 QA 및 승인된 배포 절차에서만 호출한다. 같은 ID·버전의 내용 변경은 전체 rollback한다. */
    suspend fun importManifest() = transactions.write {
        require(manifest.items.map { it.id to it.version }.distinct().size == manifest.items.size)
        val now = nowUtc()
        manifest.items.forEach { candidate ->
            require(candidate.status in setOf(CuratedReviewStatus.DRAFT,
                CuratedReviewStatus.AUTO_REVIEWED, CuratedReviewStatus.REVIEW_REQUIRED)) {
                "WRITING_APPROVAL_REQUIRES_OWNER_MANIFEST"
            }
            val prior = CuratedCatalogTable.selectAll().where {
                (CuratedCatalogTable.id eq candidate.id) and (CuratedCatalogTable.version eq candidate.version)
            }.singleOrNull()
            val approval = approvals[candidate.id to candidate.version]
            val status = if (approval?.contentHash == candidate.contentHash)
                CuratedReviewStatus.APPROVED else candidate.status
            val approvedBy = if (status == CuratedReviewStatus.APPROVED) approval?.approver else null
            val recordedHash = if (status == CuratedReviewStatus.APPROVED) approvalManifestHash else null
            if (prior != null) {
                require(prior[CuratedCatalogTable.contentHash] == candidate.contentHash &&
                    prior[CuratedCatalogTable.releaseId] == candidate.releaseId) { "WRITING_CONTENT_VERSION_CONFLICT" }
                if (prior[CuratedCatalogTable.reviewStatus] != status.name ||
                    prior[CuratedCatalogTable.approvedBy] != approvedBy ||
                    prior[CuratedCatalogTable.approvalManifestHash] != recordedHash) {
                    CuratedCatalogTable.update({ (CuratedCatalogTable.id eq candidate.id) and
                        (CuratedCatalogTable.version eq candidate.version) }) {
                        it[reviewStatus] = status.name
                        it[CuratedCatalogTable.approvedBy] = approvedBy
                        it[CuratedCatalogTable.approvalManifestHash] = recordedHash
                        it[updatedAt] = now
                    }
                }
            } else {
                CuratedCatalogTable.insert {
                    it[id] = candidate.id
                    it[version] = candidate.version
                    it[contentHash] = candidate.contentHash
                    it[releaseId] = candidate.releaseId
                    it[reviewStatus] = status.name
                    it[CuratedCatalogTable.approvedBy] = approvedBy
                    it[CuratedCatalogTable.approvalManifestHash] = recordedHash
                    it[originLanguage] = candidate.originLanguage
                    it[learningLanguage] = candidate.learningLanguage
                    it[writingType] = candidate.writingType.name
                    it[targetBand] = candidate.band
                    it[semanticKey] = candidate.semanticKey
                    it[topicKey] = candidate.topicKey
                    it[contentJson] = content(candidate).toString()
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
        }
    }

    suspend fun start(request: Start): JsonObject = transactions.write {
        require(request.userId > 0 && request.originLanguage.isNotBlank() && request.learningLanguage.isNotBlank())
        val now = nowUtc()
        lockOwner(request.userId, now)

        // 같은 날짜·유형의 재전송은 저장된 정책과 세트를 그대로 반환한다.
        val existing = findSet(request.userId, request.date, request.type)
        if (existing != null) {
            require(existing[CuratedSetsTable.policyVersion] == CuratedWritingSelection.policyVersion) {
                "WRITING_POLICY_CONFLICT"
            }
            return@write renderSet(existing, request.userId, request.date, request.reviewDays)
        }
        require(WritingSetsTable.selectAll().where {
            (WritingSetsTable.userId eq request.userId) and
                (WritingSetsTable.learningDate eq request.date) and
                (WritingSetsTable.writingType eq request.type.name)
        }.empty()) { "WRITING_POLICY_CONFLICT" }

        // 현재 레벨 테스트 결과만 단계 기준으로 읽는다. 신규 피드백은 이 값을 갱신하지 않는다.
        val profile = ExposedWritingSetUnitOfWork.currentGrowthProfile(request.userId)
        require(profile != null && profile.state != "LEVEL_TEST_REQUIRED") { "LEVEL_TEST_REQUIRED" }
        val baseBand = when (val score = profile.baseLevelScore) {
            null -> 2
            else -> when {
                score < 40 -> 1
                score < 55 -> 2
                score < 70 -> 3
                score < 85 -> 4
                else -> 5
            }
        }
        val eligible = eligible(request.originLanguage, request.learningLanguage, request.type)
        val recent = recentIds(request.userId)
        val selected = CuratedWritingSelection.select(
            eligible, manifest.releaseId, request.originLanguage, request.learningLanguage,
            request.type, baseBand, recent, request.rePractice,
            "${request.userId}|${request.date}|${request.type}", request.qaOnly,
        ) ?: run {
            val canRePractice = !request.rePractice && CuratedWritingSelection.select(
                eligible, manifest.releaseId, request.originLanguage, request.learningLanguage,
                request.type, baseBand, emptySet(), true,
                "${request.userId}|${request.date}|${request.type}", request.qaOnly,
            ) != null
            error(if (canRePractice) "WRITING_REPRACTICE_REQUIRED" else "WRITING_CONTENT_NOT_AVAILABLE")
        }

        // 다섯 문항이 준비된 후에만 세션·snapshot·학습 준비를 한 트랜잭션에 보관한다.
        val setId = CuratedSetsTable.insert {
            it[userId] = request.userId
            it[learningDate] = request.date
            it[writingType] = request.type.name
            it[policyVersion] = CuratedWritingSelection.policyVersion
            it[resultPolicy] = "REFERENCE_ONLY"
            it[releaseId] = manifest.releaseId
            it[originLanguage] = request.originLanguage
            it[learningLanguage] = request.learningLanguage
            it[CuratedSetsTable.baseBand] = baseBand
            it[status] = "READY"
            it[rePractice] = request.rePractice
            it[replacementCount] = 0
            it[createdAt] = now
            it[updatedAt] = now
        }[CuratedSetsTable.id]
        selected.slots.forEach { (slot, candidate) -> insertItem(request.userId, setId, slot, candidate, now) }
        ExposedWritingSetUnitOfWork.markLearningPrepared(request.userId, request.date, now)
        renderSet(checkNotNull(findSetById(request.userId, setId)), request.userId, request.date, request.reviewDays)
    }

    suspend fun byId(userId: Long, setId: Long, today: LocalDate, reviewDays: Int): JsonObject? = transactions.read {
        findSetById(userId, setId)?.takeIf { it[CuratedSetsTable.policyVersion] == CuratedWritingSelection.policyVersion }
            ?.let { renderSet(it, userId, today, reviewDays) }
    }

    suspend fun byDate(userId: Long, date: LocalDate, type: WritingType, today: LocalDate, reviewDays: Int): JsonObject? =
        transactions.read {
            findSet(userId, date, type)?.takeIf { it[CuratedSetsTable.policyVersion] == CuratedWritingSelection.policyVersion }
                ?.let { renderSet(it, userId, today, reviewDays) }
        }

    suspend fun submit(
        userId: Long, itemId: Long, answer: String, revision: String, today: LocalDate, reviewDays: Int,
        expectedPolicy: String = CuratedWritingSelection.policyVersion,
    ): JsonObject = transactions.write {
        require(answer.isNotBlank() && answer.length <= 10000) { "WRITING_ANSWER_REQUIRED" }
        require(reviewDays > 0)
        val now = nowUtc()
        lockOwner(userId, now)
        val item = findItem(userId, itemId) ?: error("WRITING_ITEM_NOT_FOUND")
        val set = findSetById(userId, item[CuratedItemsTable.setId]) ?: error("WRITING_SET_NOT_FOUND")
        require(set[CuratedSetsTable.policyVersion] == expectedPolicy) { "WRITING_POLICY_CONFLICT" }
        require(revision == item[CuratedItemsTable.contentRevision]) { "WRITING_ITEM_STALE" }
        require(!today.isBefore(set[CuratedSetsTable.learningDate]) &&
            !today.isAfter(set[CuratedSetsTable.learningDate].plusDays(reviewDays - 1L))) { "WRITING_REVIEW_EXPIRED" }
        val old = CuratedAnswersTable.selectAll().where {
            (CuratedAnswersTable.userId eq userId) and (CuratedAnswersTable.itemId eq itemId) and
                (CuratedAnswersTable.attemptDate eq today)
        }.singleOrNull()

        // 같은 요청의 재전송만 멱등으로 처리한다. 서로 다른 답은 기존 원문을 덮지 않는다.
        if (old == null) {
            CuratedAnswersTable.insert {
                it[CuratedAnswersTable.itemId] = itemId
                it[CuratedAnswersTable.userId] = userId
                it[attemptDate] = today
                it[answerText] = answer.trim()
                it[contentRevision] = revision
                it[submittedAt] = now
            }
        } else {
            require(old[CuratedAnswersTable.answerText] == answer.trim() &&
                old[CuratedAnswersTable.contentRevision] == revision) { "WRITING_ANSWER_NOT_ALLOWED" }
        }
        val allItems = setItems(userId, set[CuratedSetsTable.id])
        val answered = setAnswers(userId, allItems.map { it[CuratedItemsTable.id] }).keys
        if (allItems.size == set[CuratedSetsTable.targetItemCount] &&
            allItems.all { it[CuratedItemsTable.id] in answered }) {
            CuratedSetsTable.update({ (CuratedSetsTable.id eq set[CuratedSetsTable.id]) and
                (CuratedSetsTable.userId eq userId) }) {
                it[status] = "COMPLETED"
                it[updatedAt] = now
            }
        }
        renderSet(checkNotNull(findSetById(userId, set[CuratedSetsTable.id])), userId, today, reviewDays)
    }

    suspend fun replace(userId: Long, setId: Long, today: LocalDate, reviewDays: Int,
                        rePractice: Boolean, qaOnly: Boolean): JsonObject =
        transactions.write {
            val now = nowUtc()
            lockOwner(userId, now)
            val set = findSetById(userId, setId) ?: error("WRITING_SET_NOT_FOUND")
            require(set[CuratedSetsTable.policyVersion] == CuratedWritingSelection.policyVersion) {
                "WRITING_POLICY_CONFLICT"
            }
            require(!today.isBefore(set[CuratedSetsTable.learningDate]) &&
                !today.isAfter(set[CuratedSetsTable.learningDate].plusDays(reviewDays - 1L))) {
                "WRITING_REVIEW_EXPIRED"
            }
            require(set[CuratedSetsTable.replacementCount] < 3) { "WRITING_REGENERATION_LIMIT" }
            val all = setItems(userId, setId)
            val targets = all.filter { answersFor(userId, it[CuratedItemsTable.id]).isEmpty() }
            require(targets.isNotEmpty()) { "WRITING_NO_UNANSWERED_ITEM" }
            val retained = all.filterNot { it in targets }
            val slots = targets.map {
                CuratedSlot(it[CuratedItemsTable.itemOrder],
                    WritingDifficulty.valueOf(it[CuratedItemsTable.difficulty]), it[CuratedItemsTable.targetBand])
            }
            val candidates = eligible(set[CuratedSetsTable.originLanguage], set[CuratedSetsTable.learningLanguage],
                WritingType.valueOf(set[CuratedSetsTable.writingType]))
            val selected = CuratedWritingSelection.select(
                candidates, set[CuratedSetsTable.releaseId], set[CuratedSetsTable.originLanguage],
                set[CuratedSetsTable.learningLanguage], WritingType.valueOf(set[CuratedSetsTable.writingType]),
                set[CuratedSetsTable.baseBand], recentIds(userId), rePractice,
                "${userId}|${setId}|${set[CuratedSetsTable.replacementCount] + 1}", qaOnly,
                requestedSlots = slots,
                blockedIds = all.map { it[CuratedItemsTable.catalogId] }.toSet(),
                blockedSemanticKeys = retained.map { it[CuratedItemsTable.semanticKey] }.toSet(),
            ) ?: error("WRITING_CONTENT_NOT_AVAILABLE")

            // 기존 답안이 없는 각 슬롯의 revision만 새 값으로 바꾼다. 하나라도 충돌하면 전체 rollback한다.
            val replacements = selected.slots.associate { it.first.order to it.second }
            targets.forEach { old ->
                val next = checkNotNull(replacements[old[CuratedItemsTable.itemOrder]])
                val changed = CuratedItemsTable.update({
                    (CuratedItemsTable.id eq old[CuratedItemsTable.id]) and
                        (CuratedItemsTable.userId eq userId) and
                        (CuratedItemsTable.contentRevision eq old[CuratedItemsTable.contentRevision])
                }) {
                    it[catalogId] = next.id
                    it[catalogVersion] = next.version
                    it[contentHash] = next.contentHash
                    it[semanticKey] = next.semanticKey
                    it[publicJson] = publicContent(next).toString()
                    it[privateJson] = privateContent(next).toString()
                    it[contentRevision] = revision(next, setId, old[CuratedItemsTable.itemOrder],
                        set[CuratedSetsTable.replacementCount] + 1)
                    it[updatedAt] = now
                }
                check(changed == 1 && answersFor(userId, old[CuratedItemsTable.id]).isEmpty()) {
                    "WRITING_REGENERATION_CONFLICT"
                }
            }
            CuratedSetsTable.update({ (CuratedSetsTable.id eq setId) and (CuratedSetsTable.userId eq userId) }) {
                it[replacementCount] = set[CuratedSetsTable.replacementCount] + 1
                it[updatedAt] = now
            }
            renderSet(checkNotNull(findSetById(userId, setId)), userId, today, reviewDays)
        }

    internal fun eligible(origin: String, learning: String, type: WritingType): List<CuratedWritingItem> =
        CuratedCatalogTable.selectAll().where {
            (CuratedCatalogTable.releaseId eq manifest.releaseId) and
                (CuratedCatalogTable.originLanguage eq origin) and
                (CuratedCatalogTable.learningLanguage eq learning) and
                (CuratedCatalogTable.writingType eq type.name)
        }.mapNotNull { row ->
            val item = catalog[row[CuratedCatalogTable.id] to row[CuratedCatalogTable.version]] ?: return@mapNotNull null
            check(item.contentHash == row[CuratedCatalogTable.contentHash]) { "WRITING_CONTENT_HASH_MISMATCH" }
            item.copy(status = CuratedReviewStatus.valueOf(row[CuratedCatalogTable.reviewStatus]))
        }

    private fun recentIds(userId: Long): Set<String> = CuratedItemsTable.selectAll()
        .where { CuratedItemsTable.userId eq userId }
        .orderBy(CuratedItemsTable.id, SortOrder.DESC).limit(50)
        .map { it[CuratedItemsTable.catalogId] }.toSet()

    internal fun insertItem(userId: Long, setId: Long, slot: CuratedSlot, item: CuratedWritingItem, now: LocalDateTime,
                            policy: String = CuratedWritingSelection.policyVersion) {
        CuratedItemsTable.insert {
            it[CuratedItemsTable.setId] = setId
            it[CuratedItemsTable.userId] = userId
            it[itemOrder] = slot.order
            it[itemPolicyVersion] = policy
            it[difficulty] = slot.difficulty.name
            it[targetBand] = slot.targetBand
            it[catalogId] = item.id
            it[catalogVersion] = item.version
            it[contentHash] = item.contentHash
            it[semanticKey] = item.semanticKey
            it[publicJson] = publicContent(item).toString()
            it[privateJson] = privateContent(item).toString()
            it[contentRevision] = revision(item, setId, slot.order, 0)
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    internal fun renderSet(set: ResultRow, userId: Long, today: LocalDate, reviewDays: Int): JsonObject {
        val entries = setItems(userId, set[CuratedSetsTable.id])
        val answersByItem = setAnswers(userId, entries.map { it[CuratedItemsTable.id] })
        val reviewAvailable = reviewDays > 0 && !today.isBefore(set[CuratedSetsTable.learningDate]) &&
            !today.isAfter(set[CuratedSetsTable.learningDate].plusDays(reviewDays - 1L))
        return buildJsonObject {
            put("dailySetId", LearningPublicId.encode(set[CuratedSetsTable.id]))
            put("learningDate", set[CuratedSetsTable.learningDate].toString())
            put("writingType", set[CuratedSetsTable.writingType])
            put("policyVersion", set[CuratedSetsTable.policyVersion])
            put("resultPolicy", set[CuratedSetsTable.resultPolicy])
            put("releaseId", set[CuratedSetsTable.releaseId])
            put("originLanguage", set[CuratedSetsTable.originLanguage])
            put("learningLanguage", set[CuratedSetsTable.learningLanguage])
            put("baseBand", set[CuratedSetsTable.baseBand])
            put("status", set[CuratedSetsTable.status])
            put("sentenceCount", set[CuratedSetsTable.targetItemCount])
            put("generatedItemCount", entries.size)
            put("replacementCount", set[CuratedSetsTable.replacementCount])
            put("rePractice", set[CuratedSetsTable.rePractice])
            put("reviewAvailable", reviewAvailable)
            put("personalizedFeedbackStatus", "DISABLED_PENDING_QUALITY_GATE")
            put("items", JsonArray(entries.map { row ->
                val answers = answersByItem[row[CuratedItemsTable.id]].orEmpty()
                val content = Json.parseToJsonElement(row[CuratedItemsTable.publicJson]).jsonObject
                buildJsonObject {
                    put("itemId", LearningPublicId.encode(row[CuratedItemsTable.id]))
                    put("order", row[CuratedItemsTable.itemOrder])
                    put("difficulty", row[CuratedItemsTable.difficulty])
                    put("targetBand", row[CuratedItemsTable.targetBand])
                    put("catalogId", row[CuratedItemsTable.catalogId])
                    put("catalogVersion", row[CuratedItemsTable.catalogVersion])
                    put("contentRevision", row[CuratedItemsTable.contentRevision])
                    content.forEach { (key, value) -> put(key, value) }
                    put("answered", answers.isNotEmpty())
                    put("canSubmit", reviewAvailable && answers.none { it[CuratedAnswersTable.attemptDate] == today })
                    put("attempts", JsonArray(answers.map { answer ->
                        buildJsonObject {
                            put("answerId", LearningPublicId.encode(answer[CuratedAnswersTable.id]))
                            put("attemptDate", answer[CuratedAnswersTable.attemptDate].toString())
                            put("answer", answer[CuratedAnswersTable.answerText])
                            put("submittedAt", answer[CuratedAnswersTable.submittedAt].toString())
                            put("resultPolicy", "REFERENCE_ONLY")
                            put("evaluation", JsonNull)
                        }
                    }))
                    // 비공개 체크리스트와 참고 답안은 해당 문항의 최초 제출 뒤에만 공개한다.
                    if (answers.isNotEmpty()) {
                        put("reference", Json.parseToJsonElement(row[CuratedItemsTable.privateJson]))
                    }
                }
            }))
        }
    }

    internal fun findSet(userId: Long, date: LocalDate, type: WritingType): ResultRow? =
        CuratedSetsTable.selectAll().where { (CuratedSetsTable.userId eq userId) and
            (CuratedSetsTable.learningDate eq date) and (CuratedSetsTable.writingType eq type.name) }.singleOrNull()

    internal fun findSetById(userId: Long, id: Long): ResultRow? = CuratedSetsTable.selectAll().where {
        (CuratedSetsTable.id eq id) and (CuratedSetsTable.userId eq userId)
    }.singleOrNull()

    internal fun findItem(userId: Long, id: Long): ResultRow? = CuratedItemsTable.selectAll().where {
        (CuratedItemsTable.id eq id) and (CuratedItemsTable.userId eq userId)
    }.singleOrNull()

    internal fun setItems(userId: Long, setId: Long): List<ResultRow> = CuratedItemsTable.selectAll().where {
        (CuratedItemsTable.userId eq userId) and (CuratedItemsTable.setId eq setId)
    }.orderBy(CuratedItemsTable.itemOrder).toList()

    private fun answersFor(userId: Long, itemId: Long): List<ResultRow> = CuratedAnswersTable.selectAll().where {
        (CuratedAnswersTable.userId eq userId) and (CuratedAnswersTable.itemId eq itemId)
    }.orderBy(CuratedAnswersTable.attemptDate).toList()

    // 확보 수가 늘어나도 문항마다 별도 답안 쿼리를 만들지 않는다. 반환 순서는 기존 날짜 순서다.
    internal fun setAnswers(userId: Long, itemIds: List<Long>): Map<Long, List<ResultRow>> =
        if (itemIds.isEmpty()) emptyMap() else CuratedAnswersTable.selectAll().where {
            (CuratedAnswersTable.userId eq userId) and (CuratedAnswersTable.itemId inList itemIds)
        }.orderBy(CuratedAnswersTable.attemptDate).groupBy { it[CuratedAnswersTable.itemId] }

    private fun lockOwner(userId: Long, now: LocalDateTime) {
        ExposedWritingSetUnitOfWork.lockCurrentOwner(userId, now)
    }

    private fun nowUtc(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        .truncatedTo(ChronoUnit.MICROS)

    private fun revision(item: CuratedWritingItem, setId: Long, order: Int, replacement: Int): String =
        CuratedWritingManifestCodec.sha256("${item.contentHash}|$setId|$order|$replacement")

    private fun content(item: CuratedWritingItem): JsonObject = buildJsonObject {
        put("public", publicContent(item))
        put("private", privateContent(item))
        put("levelEvidence", item.levelEvidence)
        put("validAlternative", item.validAlternative)
        put("clearError", item.clearError)
        put("boundaryAnswer", item.boundaryAnswer)
    }

    private fun publicContent(item: CuratedWritingItem): JsonObject = buildJsonObject {
        put("originText", item.prompt)
        put("providedFacts", JsonArray(item.providedFacts.map(::JsonPrimitive)))
        put("requiredIntents", JsonArray(item.requiredIntents.map(::JsonPrimitive)))
        put("responseConstraints", JsonArray(item.responseConstraints.map(::JsonPrimitive)))
        put("topicKey", item.topicKey)
    }

    private fun privateContent(item: CuratedWritingItem): JsonObject = buildJsonObject {
        put("checklist", JsonArray(item.checklist.map(::JsonPrimitive)))
        put("referenceAnswers", JsonArray(item.referenceAnswers.map(::JsonPrimitive)))
        put("alternativeNote", item.alternativeNote)
        put("isOnlyCorrectAnswer", false)
    }
}
