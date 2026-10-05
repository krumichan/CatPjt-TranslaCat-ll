package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackPolicy
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingManifestCodec
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.*
import jp.co.translacat.languagelearning.shared.ai.*
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 답안은 먼저 저장되고 모델 호출은 트랜잭션 밖에서 진행한다. 점수형 평가 테이블을 사용하지 않는다. */
internal class CuratedWritingFeedbackWorker(
    private val transactions: JdbcTransactionRunner,
    private val snapshots: CuratedWritingStore,
    private val releaseId: String,
    private val model: ModelExecutionPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val pendingValidated = ConcurrentHashMap<Long, Validated>()

    private data class Claim(
        val answerId: Long,
        val token: String,
        val attempt: Int,
        val answer: String,
        val public: JsonObject,
        val reference: JsonObject,
        val requirements: List<Pair<String, String>>,
        val segments: List<CuratedWritingFeedbackPolicy.Segment>,
    )

    private data class Validated(
        val token: String,
        val payload: JsonObject,
        val provider: String,
        val model: String,
        val inputTokens: Int,
        val outputTokens: Int,
    )

    suspend fun submitted(request: CuratedWritingStore.Start, itemId: Long) {
        // 이미 저장된 오늘의 답안을 찾아 단일 job에 연결한다. 재전송은 같은 답안 ID를 재사용한다.
        val answerId = transactions.read {
            val item = snapshots.findItem(request.userId, itemId) ?: error("WRITING_ITEM_NOT_FOUND")
            val set = snapshots.findSetById(request.userId, item[CuratedItemsTable.setId]) ?: error("WRITING_SET_NOT_FOUND")
            require(set[CuratedSetsTable.policyVersion] == "curated-writing-variable-n-v1") { "WRITING_POLICY_CONFLICT" }
            require(set[CuratedSetsTable.originLanguage] == request.originLanguage &&
                set[CuratedSetsTable.learningLanguage] == request.learningLanguage) { "WRITING_LANGUAGE_CONFLICT" }
            CuratedAnswersTable.selectAll().where {
                (CuratedAnswersTable.itemId eq itemId) and (CuratedAnswersTable.userId eq request.userId) and
                    (CuratedAnswersTable.attemptDate eq request.date)
            }.singleOrNull()?.get(CuratedAnswersTable.id) ?: error("WRITING_ANSWER_REQUIRED")
        }
        run(request, answerId, allowRetry = false)
    }

    suspend fun retry(request: CuratedWritingStore.Start, answerId: Long) {
        run(request, answerId, allowRetry = true)
    }

    /** 호출 중 DB 저장만 실패했다면 살아 있는 worker의 검증 결과를 먼저 저장한다. 재기동 후 무과금은 주장하지 않는다. */
    private suspend fun run(request: CuratedWritingStore.Start, answerId: Long, allowRetry: Boolean) {
        if (pendingValidated.containsKey(answerId)) {
            saveValidated(request.userId, answerId)
            return
        }
        val claim = claim(request, answerId, allowRetry) ?: return
        val verified = try {
            val output = model.execute(ModelExecutionCommand(
                traceId = "curated-feedback-$answerId-${claim.attempt}",
                instructions = CuratedWritingFeedbackPolicy.instructions,
                messages = listOf(ModelMessage("user", CuratedWritingFeedbackPolicy.prompt(
                    claim.public, claim.reference, claim.answer, claim.requirements, claim.segments,
                ))),
                tier = ModelTier.MINI,
                maxOutputTokens = 4096,
                deadlineUtc = clock.instant().plusSeconds(90),
                responseSchema = CuratedWritingFeedbackPolicy.schema(claim.requirements, claim.segments),
                schemaName = "curated_writing_feedback",
                strict = true,
                taskName = "LANGUAGE_LEARNING_CURATED_WRITING_FEEDBACK",
            ))
            // 현재 서비스의 MINI/OpenAI 계약이 바뀌었다면 QA 결과를 성공으로 저장하지 않는다.
            // OpenAI 응답은 요청 별칭 대신 현재 고정 snapshot 이름을 돌려준다.
            require(output.provider == "openai" && output.model in setOf("gpt-5-mini", "gpt-5-mini-2025-08-07")) {
                "WRITING_FEEDBACK_MODEL_CHANGED"
            }
            val feedback = CuratedWritingFeedbackPolicy.validate(output.output, claim.requirements, claim.segments)
            Validated(claim.token, feedback, output.provider, output.model,
                output.inputTokens, output.outputTokens)
        } catch (cancelled: CancellationException) {
            // 전송 결과가 불명인 취소를 자동 재전송하지 않는다.
            markFailure(request.userId, answerId, claim.token, "UNCERTAIN", "MODEL_CANCELLED")
            throw cancelled
        } catch (failure: ModelExecutionFailure) {
            markFailure(request.userId, answerId, claim.token, "UNCERTAIN", failure.code)
            return
        } catch (failure: IllegalArgumentException) {
            markFailure(request.userId, answerId, claim.token, "FAILED", "FEEDBACK_VALIDATION_FAILED")
            return
        }
        // 검증된 결과를 메모리에 묶은 뒤 저장한다. DB 오류는 모델 실패로 재분류하거나 재과금하지 않는다.
        pendingValidated[answerId] = verified
        saveValidated(request.userId, answerId)
    }

    private suspend fun claim(request: CuratedWritingStore.Start, answerId: Long, allowRetry: Boolean): Claim? =
        transactions.write {
            val now = nowUtc()
            ExposedWritingSetUnitOfWork.lockCurrentOwner(request.userId, now)
            val answer = CuratedAnswersTable.selectAll().where {
                (CuratedAnswersTable.id eq answerId) and (CuratedAnswersTable.userId eq request.userId)
            }.singleOrNull() ?: error("WRITING_ANSWER_NOT_FOUND")
            val item = snapshots.findItem(request.userId, answer[CuratedAnswersTable.itemId]) ?: error("WRITING_ITEM_NOT_FOUND")
            val set = snapshots.findSetById(request.userId, item[CuratedItemsTable.setId]) ?: error("WRITING_SET_NOT_FOUND")
            require(set[CuratedSetsTable.policyVersion] == "curated-writing-variable-n-v1" &&
                set[CuratedSetsTable.releaseId] == releaseId) { "WRITING_POLICY_CONFLICT" }
            require(set[CuratedSetsTable.originLanguage] == request.originLanguage &&
                set[CuratedSetsTable.learningLanguage] == request.learningLanguage) { "WRITING_LANGUAGE_CONFLICT" }
            require(request.reviewDays > 0 && !request.date.isBefore(set[CuratedSetsTable.learningDate]) &&
                !request.date.isAfter(set[CuratedSetsTable.learningDate].plusDays(request.reviewDays - 1L))) {
                "WRITING_REVIEW_EXPIRED"
            }
            require(answer[CuratedAnswersTable.contentRevision] == item[CuratedItemsTable.contentRevision]) {
                "WRITING_ITEM_STALE"
            }
            val public = Json.parseToJsonElement(item[CuratedItemsTable.publicJson]).jsonObject
            val reference = Json.parseToJsonElement(item[CuratedItemsTable.privateJson]).jsonObject
            val text = answer[CuratedAnswersTable.answerText]
            val requirements = CuratedWritingFeedbackPolicy.requirements(reference.getValue("checklist").jsonArray)
            val segments = CuratedWritingFeedbackPolicy.segments(text)
            val answerHash = CuratedWritingManifestCodec.sha256(text)
            val sourceHash = CuratedWritingManifestCodec.sha256(
                "${item[CuratedItemsTable.contentRevision]}|${item[CuratedItemsTable.publicJson]}|${item[CuratedItemsTable.privateJson]}",
            )
            val requestHash = CuratedWritingManifestCodec.sha256(
                "${request.userId}|$answerId|$answerHash|$sourceHash|${CuratedWritingFeedbackPolicy.version}",
            )
            var row = CuratedWritingFeedbackTable.selectAll().where {
                CuratedWritingFeedbackTable.answerId eq answerId
            }.singleOrNull()
            if (row == null) {
                CuratedWritingFeedbackTable.insert {
                    it[CuratedWritingFeedbackTable.answerId] = answerId
                    it[CuratedWritingFeedbackTable.itemId] = item[CuratedItemsTable.id]
                    it[setId] = set[CuratedSetsTable.id]
                    it[userId] = request.userId
                    it[policyVersion] = CuratedWritingFeedbackPolicy.version
                    it[status] = "PENDING"
                    it[CuratedWritingFeedbackTable.answerHash] = answerHash
                    it[contentRevision] = item[CuratedItemsTable.contentRevision]
                    it[CuratedWritingFeedbackTable.sourceHash] = sourceHash
                    it[CuratedWritingFeedbackTable.requestHash] = requestHash
                    it[attemptCount] = 0
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                row = checkNotNull(CuratedWritingFeedbackTable.selectAll().where {
                    CuratedWritingFeedbackTable.answerId eq answerId
                }.singleOrNull())
            }
            val current = checkNotNull(row)
            require(current[CuratedWritingFeedbackTable.userId] == request.userId &&
                current[CuratedWritingFeedbackTable.itemId] == item[CuratedItemsTable.id] &&
                current[CuratedWritingFeedbackTable.setId] == set[CuratedSetsTable.id] &&
                current[CuratedWritingFeedbackTable.answerHash] == answerHash &&
                current[CuratedWritingFeedbackTable.contentRevision] == item[CuratedItemsTable.contentRevision] &&
                current[CuratedWritingFeedbackTable.sourceHash] == sourceHash &&
                current[CuratedWritingFeedbackTable.requestHash] == requestHash) { "WRITING_FEEDBACK_STALE" }
            val state = current[CuratedWritingFeedbackTable.status]
            if (state == "SUCCEEDED") return@write null
            if (state == "UNCERTAIN") error("WRITING_FEEDBACK_UNCERTAIN")
            if (state == "PROCESSING") {
                if (current[CuratedWritingFeedbackTable.claimUntil]?.isAfter(now) == true) return@write null
                error("WRITING_FEEDBACK_UNCERTAIN")
            }
            if (state == "FAILED" && !allowRetry) return@write null
            require(state == "PENDING" || state == "FAILED" && allowRetry) { "WRITING_FEEDBACK_BUSY" }
            require(current[CuratedWritingFeedbackTable.attemptCount] < 2) { "WRITING_FEEDBACK_RETRY_LIMIT" }
            val token = UUID.randomUUID().toString()
            val attempt = current[CuratedWritingFeedbackTable.attemptCount] + 1
            CuratedWritingFeedbackTable.update({ CuratedWritingFeedbackTable.answerId eq answerId }) {
                it[status] = "PROCESSING"
                it[claimToken] = token
                it[claimUntil] = now.plusSeconds(180)
                it[attemptCount] = attempt
                it[failureCode] = null
                it[updatedAt] = now
            }
            Claim(answerId, token, attempt, text, public, reference, requirements, segments)
        }

    private suspend fun saveValidated(userId: Long, answerId: Long) {
        val result = pendingValidated[answerId] ?: error("WRITING_FEEDBACK_VERIFIED_RESULT_MISSING")
        transactions.write {
            ExposedWritingSetUnitOfWork.lockCurrentOwner(userId, nowUtc())
            val row = CuratedWritingFeedbackTable.selectAll().where {
                (CuratedWritingFeedbackTable.answerId eq answerId) and (CuratedWritingFeedbackTable.userId eq userId)
            }.singleOrNull() ?: error("WRITING_ANSWER_NOT_FOUND")
            require(row[CuratedWritingFeedbackTable.status] == "PROCESSING" &&
                row[CuratedWritingFeedbackTable.claimToken] == result.token) { "WRITING_FEEDBACK_STALE" }
            CuratedWritingFeedbackTable.update({ CuratedWritingFeedbackTable.answerId eq answerId }) {
                it[status] = "SUCCEEDED"
                it[resultJson] = result.payload.toString()
                it[resultHash] = CuratedWritingManifestCodec.sha256(result.payload.toString())
                it[provider] = result.provider
                it[model] = result.model
                it[inputTokens] = result.inputTokens
                it[outputTokens] = result.outputTokens
                it[claimToken] = null
                it[claimUntil] = null
                it[failureCode] = null
                it[updatedAt] = nowUtc()
            }
        }
        pendingValidated.remove(answerId, result)
    }

    private suspend fun markFailure(userId: Long, answerId: Long, token: String, status: String, code: String) {
        transactions.write {
            ExposedWritingSetUnitOfWork.lockCurrentOwner(userId, nowUtc())
            CuratedWritingFeedbackTable.update({
                (CuratedWritingFeedbackTable.answerId eq answerId) and
                    (CuratedWritingFeedbackTable.userId eq userId) and
                    (CuratedWritingFeedbackTable.claimToken eq token) and
                    (CuratedWritingFeedbackTable.status eq "PROCESSING")
            }) {
                it[CuratedWritingFeedbackTable.status] = status
                it[claimToken] = null
                it[claimUntil] = null
                it[failureCode] = code.take(80)
                it[updatedAt] = nowUtc()
            }
        }
    }

    /** 조회와 재진입은 DB만 읽으며 외부 요청을 생성하지 않는다. */
    fun byAnswerIds(userId: Long, answerIds: List<Long>): Map<Long, ResultRow> =
        if (answerIds.isEmpty()) emptyMap() else CuratedWritingFeedbackTable.selectAll().where {
            (CuratedWritingFeedbackTable.userId eq userId) and (CuratedWritingFeedbackTable.answerId inList answerIds)
        }.associateBy { it[CuratedWritingFeedbackTable.answerId] }

    fun publicState(row: ResultRow?): JsonObject = buildJsonObject {
        val hasSavedResult = row != null && pendingValidated.containsKey(row[CuratedWritingFeedbackTable.answerId])
        val state = if (row == null) "PENDING" else {
            val stored = row[CuratedWritingFeedbackTable.status]
            if (stored == "PROCESSING" && !hasSavedResult &&
                row[CuratedWritingFeedbackTable.claimUntil]?.isAfter(nowUtc()) != true)
                "UNCERTAIN" else stored
        }
        put("status", state)
        put("result", if (state == "SUCCEEDED")
            row?.get(CuratedWritingFeedbackTable.resultJson)?.let(Json::parseToJsonElement) ?: JsonNull else JsonNull)
        put("failureCode", if (state in setOf("FAILED", "UNCERTAIN"))
            row?.get(CuratedWritingFeedbackTable.failureCode)?.let(::JsonPrimitive) ?: JsonNull else JsonNull)
        put("canRetry", row != null && (state == "FAILED" && row[CuratedWritingFeedbackTable.attemptCount] < 2 ||
            state == "PROCESSING" && hasSavedResult))
    }

    private fun nowUtc(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        .truncatedTo(ChronoUnit.MICROS)
}
