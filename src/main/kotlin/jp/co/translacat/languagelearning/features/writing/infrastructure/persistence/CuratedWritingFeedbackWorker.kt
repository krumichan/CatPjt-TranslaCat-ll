package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackPolicy
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackV3Policy
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackV4Policy
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackV5Policy
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackV6Policy
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackV7Policy
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackV9Policy
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackV10Policy
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackV11Policy
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackV12Policy
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingManifestCodec
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.learner.domain.exception.LearnerUnavailableException
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
        val policyVersion: String,
        val originLanguage: String,
        val learningLanguage: String,
        val writingType: WritingType,
        val answer: String,
        val public: JsonObject,
        val reference: JsonObject,
        val requirements: List<Pair<String, String>>,
        val segments: List<CuratedWritingFeedbackV3Policy.Segment>,
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
            // 구형 재시도는 당시 계약으로 실행한다. 새 답안의 언어는 저장된 plan snapshot에서만 가져온다.
            val context = CuratedWritingFeedbackPolicy.Context(claim.writingType, claim.public,
                claim.originLanguage, claim.learningLanguage)
            val v4Context = CuratedWritingFeedbackV4Policy.Context(claim.writingType, claim.public,
                claim.originLanguage, claim.learningLanguage)
            val v5Context = CuratedWritingFeedbackV5Policy.Context(claim.writingType, claim.public,
                claim.originLanguage, claim.learningLanguage)
            val v6Context = CuratedWritingFeedbackV6Policy.Context(claim.writingType, claim.public,
                claim.originLanguage, claim.learningLanguage)
            val v7Context = CuratedWritingFeedbackV7Policy.Context(claim.writingType, claim.public,
                claim.originLanguage, claim.learningLanguage)
            val (prompt, instructions, schema) = when (claim.policyVersion) {
                CuratedWritingFeedbackV3Policy.version -> Triple(
                    CuratedWritingFeedbackV3Policy.prompt(claim.writingType, claim.public, claim.reference,
                        claim.answer, claim.requirements, claim.segments),
                    CuratedWritingFeedbackV3Policy.instructions,
                    CuratedWritingFeedbackV3Policy.schema(claim.requirements, claim.segments),
                )
                CuratedWritingFeedbackV4Policy.version -> Triple(
                    CuratedWritingFeedbackV4Policy.prompt(v4Context, claim.reference, claim.answer, claim.requirements, claim.segments),
                    CuratedWritingFeedbackV4Policy.instructions,
                    CuratedWritingFeedbackV4Policy.schema(v4Context, claim.requirements, claim.segments),
                )
                CuratedWritingFeedbackV5Policy.version -> Triple(
                    CuratedWritingFeedbackV5Policy.prompt(v5Context, claim.reference, claim.answer, claim.requirements, claim.segments),
                    CuratedWritingFeedbackV5Policy.instructions,
                    CuratedWritingFeedbackV5Policy.schema(v5Context, claim.requirements, claim.segments),
                )
                CuratedWritingFeedbackV6Policy.version -> Triple(
                    CuratedWritingFeedbackV6Policy.prompt(v6Context, claim.reference, claim.answer, claim.requirements, claim.segments),
                    CuratedWritingFeedbackV6Policy.instructions,
                    CuratedWritingFeedbackV6Policy.schema(v6Context, claim.requirements, claim.segments),
                )
                CuratedWritingFeedbackV7Policy.version -> Triple(
                    CuratedWritingFeedbackV7Policy.prompt(v7Context, claim.reference, claim.answer, claim.requirements, claim.segments),
                    CuratedWritingFeedbackV7Policy.instructions,
                    CuratedWritingFeedbackV7Policy.schema(v7Context, claim.requirements, claim.segments),
                )
                CuratedWritingFeedbackPolicy.version -> Triple(
                    CuratedWritingFeedbackPolicy.prompt(context, claim.reference, claim.answer, claim.requirements, claim.segments),
                    CuratedWritingFeedbackPolicy.instructions,
                    CuratedWritingFeedbackPolicy.schema(context, claim.requirements, claim.segments),
                )
                CuratedWritingFeedbackV9Policy.version -> Triple(
                    CuratedWritingFeedbackV9Policy.prompt(context, claim.reference, claim.answer, claim.requirements, claim.segments),
                    CuratedWritingFeedbackV9Policy.instructions,
                    CuratedWritingFeedbackV9Policy.schema(context, claim.requirements, claim.segments),
                )
                CuratedWritingFeedbackV10Policy.version -> Triple(
                    CuratedWritingFeedbackV10Policy.prompt(context, claim.reference, claim.answer, claim.requirements, claim.segments),
                    CuratedWritingFeedbackV10Policy.instructions,
                    CuratedWritingFeedbackV10Policy.schema(context, claim.requirements, claim.segments),
                )
                CuratedWritingFeedbackV11Policy.version -> Triple(
                    CuratedWritingFeedbackV11Policy.prompt(context, claim.reference, claim.answer, claim.requirements, claim.segments),
                    CuratedWritingFeedbackV11Policy.instructions,
                    CuratedWritingFeedbackV11Policy.schema(context, claim.requirements, claim.segments),
                )
                CuratedWritingFeedbackV12Policy.version -> Triple(
                    CuratedWritingFeedbackV11Policy.prompt(context, claim.reference, claim.answer, claim.requirements, claim.segments),
                    CuratedWritingFeedbackV11Policy.instructions,
                    CuratedWritingFeedbackV11Policy.schema(context, claim.requirements, claim.segments),
                )
                else -> error("WRITING_FEEDBACK_POLICY_UNSUPPORTED")
            }
            val output = model.execute(ModelExecutionCommand(
                traceId = "curated-feedback-$answerId-${claim.attempt}",
                instructions = instructions,
                messages = listOf(ModelMessage("user", prompt)),
                tier = ModelTier.MINI,
                maxOutputTokens = 4096,
                deadlineUtc = clock.instant().plusSeconds(90),
                responseSchema = schema,
                schemaName = "curated_writing_feedback",
                strict = true,
                taskName = "LANGUAGE_LEARNING_CURATED_WRITING_FEEDBACK",
            ))
            // 현재 서비스의 MINI/OpenAI 계약이 바뀌었다면 QA 결과를 성공으로 저장하지 않는다.
            // OpenAI 응답은 요청 별칭 대신 현재 고정 snapshot 이름을 돌려준다.
            require(output.provider == "openai" && output.model in setOf("gpt-5-mini", "gpt-5-mini-2025-08-07")) {
                "WRITING_FEEDBACK_MODEL_CHANGED"
            }
            val modelResults = mutableListOf(output)
            val feedback = when (claim.policyVersion) {
                CuratedWritingFeedbackV3Policy.version -> CuratedWritingFeedbackV3Policy.validate(output.output, claim.requirements, claim.segments)
                CuratedWritingFeedbackV4Policy.version -> CuratedWritingFeedbackV4Policy.validate(output.output, v4Context, claim.requirements, claim.segments)
                CuratedWritingFeedbackV5Policy.version -> CuratedWritingFeedbackV5Policy.validate(output.output, v5Context, claim.requirements, claim.segments)
                CuratedWritingFeedbackV6Policy.version -> CuratedWritingFeedbackV6Policy.validate(output.output, v6Context, claim.requirements, claim.segments)
                CuratedWritingFeedbackV7Policy.version -> CuratedWritingFeedbackV7Policy.validate(output.output, v7Context, claim.requirements, claim.segments)
                CuratedWritingFeedbackPolicy.version -> CuratedWritingFeedbackPolicy.validate(output.output, context, claim.requirements, claim.segments)
                CuratedWritingFeedbackV9Policy.version -> CuratedWritingFeedbackV9Policy.validate(output.output, context, claim.requirements, claim.segments)
                CuratedWritingFeedbackV10Policy.version -> CuratedWritingFeedbackV10Policy.validate(output.output, context, claim.requirements, claim.segments)
                CuratedWritingFeedbackV11Policy.version -> CuratedWritingFeedbackV11Policy.validate(output.output, context, claim.requirements, claim.segments)
                CuratedWritingFeedbackV12Policy.version -> {
                    // 응답 전체가 비어 있으면 복구할 근거가 없다. 실패로 남겨 명시적 재시도 계약을 보존한다.
                    require(output.output is JsonObject &&
                        output.output.keys.containsAll(setOf("answerObservations", "observations",
                            "necessaryCorrections", "optionalAlternatives", "uncertainties")) &&
                        output.output["observations"] is JsonObject &&
                        (output.output["observations"] as JsonObject).isNotEmpty() &&
                        output.output["answerObservations"] is JsonArray &&
                        (output.output["answerObservations"] as JsonArray).isNotEmpty()) {
                        "WRITING_FEEDBACK_DRAFT_EMPTY"
                    }

                    // 원본 결과를 보지 않은 좁은 의미 검사를 별도로 실행해 거짓 충족 공개를 막는다.
                    val evidence = model.execute(ModelExecutionCommand(
                        traceId = "curated-feedback-$answerId-${claim.attempt}-evidence",
                        instructions = CuratedWritingFeedbackV12Policy.independentInstructions,
                        messages = listOf(ModelMessage("user", CuratedWritingFeedbackV12Policy.independentPrompt(
                            context, claim.answer, claim.requirements,
                        ))),
                        tier = ModelTier.MINI,
                        maxOutputTokens = 2200,
                        deadlineUtc = clock.instant().plusSeconds(90),
                        responseSchema = CuratedWritingFeedbackV12Policy.independentSchema(claim.requirements),
                        schemaName = "writing_independent_evidence",
                        strict = true,
                        taskName = "LANGUAGE_LEARNING_CURATED_WRITING_FEEDBACK",
                    ))
                    require(evidence.provider == output.provider && evidence.model == output.model) {
                        "WRITING_FEEDBACK_MODEL_CHANGED"
                    }
                    modelResults += evidence
                    val independent = CuratedWritingFeedbackV12Policy.validateIndependent(
                        evidence.output, claim.answer, claim.requirements,
                    )
                    val first = try {
                        CuratedWritingFeedbackV12Policy.validateFinal(
                            output.output, independent, context, claim.requirements, claim.segments,
                        )
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                    if (first != null) first else {
                        // 최초 응답 실패의 구조 원인만 제시해 한 번 복구한다. 수정본도 같은 전체 검증기를 통과해야 한다.
                        val issues = CuratedWritingFeedbackV12Policy.repairIssues(
                            output.output, independent, context, claim.requirements,
                        )
                        val repair = model.execute(ModelExecutionCommand(
                            traceId = "curated-feedback-$answerId-${claim.attempt}-repair",
                            instructions = CuratedWritingFeedbackV12Policy.repairInstructions,
                            messages = listOf(ModelMessage("user", CuratedWritingFeedbackV12Policy.repairPrompt(
                                prompt, output.output, issues, independent,
                            ))),
                            tier = ModelTier.MINI,
                            maxOutputTokens = 4096,
                            deadlineUtc = clock.instant().plusSeconds(90),
                            responseSchema = schema,
                            schemaName = "curated_writing_feedback",
                            strict = true,
                            taskName = "LANGUAGE_LEARNING_CURATED_WRITING_FEEDBACK",
                        ))
                        require(repair.provider == output.provider && repair.model == output.model) {
                            "WRITING_FEEDBACK_MODEL_CHANGED"
                        }
                        modelResults += repair
                        CuratedWritingFeedbackV12Policy.validateFinal(
                            repair.output, independent, context, claim.requirements, claim.segments,
                        )
                    }
                }
                else -> error("WRITING_FEEDBACK_POLICY_UNSUPPORTED")
            }
            Validated(claim.token, feedback, output.provider, output.model,
                modelResults.sumOf { it.inputTokens }, modelResults.sumOf { it.outputTokens })
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
                "${request.userId}|$answerId|$answerHash|$sourceHash|${CuratedWritingFeedbackV12Policy.version}",
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
                    // 새 답안만 새 의미 기준을 사용한다. 기존 성공·실패·재시도의 저장 정책은 아래에서 보존한다.
                    it[policyVersion] = CuratedWritingFeedbackV12Policy.version
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
            // 정책 개정 이후에도 저장된 성공 결과의 identity/hash는 당시 정책으로 확인한다.
            // v1 성공 결과를 새 정책으로 재평가하거나 과거 hash를 덮어쓰지 않는다.
            val storedPolicy = current[CuratedWritingFeedbackTable.policyVersion]
            val storedRequestHash = CuratedWritingManifestCodec.sha256(
                "${request.userId}|$answerId|$answerHash|$sourceHash|$storedPolicy",
            )
            require(current[CuratedWritingFeedbackTable.userId] == request.userId &&
                current[CuratedWritingFeedbackTable.itemId] == item[CuratedItemsTable.id] &&
                current[CuratedWritingFeedbackTable.setId] == set[CuratedSetsTable.id] &&
                current[CuratedWritingFeedbackTable.answerHash] == answerHash &&
                current[CuratedWritingFeedbackTable.contentRevision] == item[CuratedItemsTable.contentRevision] &&
                current[CuratedWritingFeedbackTable.sourceHash] == sourceHash &&
                current[CuratedWritingFeedbackTable.requestHash] == storedRequestHash) { "WRITING_FEEDBACK_STALE" }
            val state = current[CuratedWritingFeedbackTable.status]
            if (state == "SUCCEEDED") return@write null
            require(storedPolicy in supportedPolicies) { "WRITING_FEEDBACK_POLICY_CONFLICT" }
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
                it[claimUntil] = now.plusSeconds(if (storedPolicy == CuratedWritingFeedbackV12Policy.version) 330 else 180)
                it[attemptCount] = attempt
                it[failureCode] = null
                it[updatedAt] = now
            }
            Claim(answerId, token, attempt, storedPolicy, set[CuratedSetsTable.originLanguage],
                set[CuratedSetsTable.learningLanguage], WritingType.valueOf(set[CuratedSetsTable.writingType]),
                text, public, reference, requirements, segments)
        }

    private suspend fun saveValidated(userId: Long, answerId: Long) {
        val result = pendingValidated[answerId] ?: error("WRITING_FEEDBACK_VERIFIED_RESULT_MISSING")
        transactions.write {
            // 삭제된 소유자·답안의 늦은 결과는 버린다. 일반 DB 장애의 검증 결과는 저장 재시도용으로 남긴다.
            try {
                ExposedWritingSetUnitOfWork.lockCurrentOwner(userId, nowUtc())
            } catch (deleted: LearnerUnavailableException) {
                pendingValidated.remove(answerId, result)
                throw deleted
            }
            val row = CuratedWritingFeedbackTable.selectAll().where {
                (CuratedWritingFeedbackTable.answerId eq answerId) and (CuratedWritingFeedbackTable.userId eq userId)
            }.singleOrNull() ?: run {
                pendingValidated.remove(answerId, result)
                error("WRITING_ANSWER_NOT_FOUND")
            }
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
        // 과거 정책의 실패 행을 현재 정책으로 덮어쓰지 않는다. 지원하지 않는 재시도 버튼도 제시하지 않는다.
        val supportsRetry = row != null && row[CuratedWritingFeedbackTable.policyVersion] in supportedPolicies
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
        put("canRetry", supportsRetry && row != null && (state == "FAILED" && row[CuratedWritingFeedbackTable.attemptCount] < 2 ||
            state == "PROCESSING" && hasSavedResult))
    }

    private fun nowUtc(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        .truncatedTo(ChronoUnit.MICROS)

    private val supportedPolicies = setOf(CuratedWritingFeedbackV3Policy.version, CuratedWritingFeedbackV4Policy.version,
        CuratedWritingFeedbackV5Policy.version, CuratedWritingFeedbackV6Policy.version, CuratedWritingFeedbackV7Policy.version,
        CuratedWritingFeedbackPolicy.version, CuratedWritingFeedbackV9Policy.version,
        CuratedWritingFeedbackV10Policy.version, CuratedWritingFeedbackV11Policy.version,
        CuratedWritingFeedbackV12Policy.version)
}
