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
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackV13Policy
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackV14Policy
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingCoreVerificationV14
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingEvidenceV14
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingOptionalSuggestionsV14
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingManifestCodec
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.learner.domain.exception.LearnerUnavailableException
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.*
import jp.co.translacat.languagelearning.shared.ai.*
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.Clock
import java.time.Instant
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
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val pendingValidated = ConcurrentHashMap<Long, Validated>()

    /** 삭제 접수 노드의 DB 복구용 개인 응답도 즉시 해제한다. 늦은 결과는 saveValidated의 owner fence가 버린다. */
    internal fun discardUser(userId: Long) {
        pendingValidated.entries.removeIf { it.value.userId == userId }
    }

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
        val userId: Long,
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
        val semanticDeadline = if (claim.policyVersion in setOf(CuratedWritingFeedbackV13Policy.version,
                CuratedWritingFeedbackV14Policy.version))
            CuratedWritingSemanticDeadline(clock, nanoTime) else null
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
            // 출제자가 고정한 의미 계약이 있는 새 정책만 분리된 관찰·근거 연결 경로를 사용한다.
            val semanticPlan = if (claim.policyVersion in setOf(CuratedWritingFeedbackV13Policy.version,
                    CuratedWritingFeedbackV14Policy.version))
                requireNotNull(CuratedWritingFeedbackV13Policy.prepare(context, claim.requirements, claim.segments)) {
                    "WRITING_MEANING_CONTRACT_UNAVAILABLE"
                } else null
            val (prompt, instructions, schema) = when (claim.policyVersion) {
                CuratedWritingFeedbackV13Policy.version, CuratedWritingFeedbackV14Policy.version -> Triple(
                    CuratedWritingFeedbackV13Policy.extractionPrompt(checkNotNull(semanticPlan), claim.answer),
                    CuratedWritingFeedbackV13Policy.extractionInstructions,
                    CuratedWritingFeedbackV13Policy.extractionSchema(checkNotNull(semanticPlan)),
                )
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
                    CuratedWritingFeedbackV12Policy.initialInstructions,
                    CuratedWritingFeedbackV11Policy.schema(context, claim.requirements, claim.segments),
                )
                else -> error("WRITING_FEEDBACK_POLICY_UNSUPPORTED")
            }
            val initialCommand = ModelExecutionCommand(
                traceId = "curated-feedback-$answerId-${claim.attempt}",
                instructions = instructions,
                messages = listOf(ModelMessage("user", prompt)),
                tier = ModelTier.MINI,
                maxOutputTokens = 4096,
                deadlineUtc = clock.instant().plusSeconds(90),
                responseSchema = schema,
                schemaName = if (semanticPlan != null) "writing_observed_claims" else "curated_writing_feedback",
                strict = true,
                taskName = "LANGUAGE_LEARNING_CURATED_WRITING_FEEDBACK",
            )
            // 의미 계약 경로의 최초 관찰도 같은 전체 시간에 포함한다. V13의 기존 시간 제한은 보존한다.
            val output = if (semanticDeadline != null) semanticDeadline.execute(model, initialCommand)
                else model.execute(initialCommand)
            // 현재 서비스의 MINI/OpenAI 계약이 바뀌었다면 QA 결과를 성공으로 저장하지 않는다.
            // OpenAI 응답은 요청 별칭 대신 현재 고정 snapshot 이름을 돌려준다.
            require(output.provider == "openai" && output.model in setOf("gpt-5-mini", "gpt-5-mini-2025-08-07")) {
                "WRITING_FEEDBACK_MODEL_CHANGED"
            }
            val modelResults = mutableListOf(output)
            val feedback = when (claim.policyVersion) {
                CuratedWritingFeedbackV14Policy.version -> serverRepairFeedback(
                    claim, checkNotNull(semanticPlan), output, modelResults, checkNotNull(semanticDeadline),
                )
                CuratedWritingFeedbackV13Policy.version -> semanticFeedback(
                    claim, checkNotNull(semanticPlan), output, modelResults, checkNotNull(semanticDeadline),
                )
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
                        instructions = CuratedWritingFeedbackV12Policy.currentIndependentInstructions,
                        messages = listOf(ModelMessage("user", CuratedWritingFeedbackV12Policy.currentIndependentPrompt(
                            context, claim.answer, claim.requirements, claim.segments,
                        ))),
                        tier = ModelTier.MINI,
                        maxOutputTokens = 2200,
                        deadlineUtc = clock.instant().plusSeconds(90),
                        responseSchema = CuratedWritingFeedbackV12Policy.currentIndependentSchema(claim.requirements, claim.segments),
                        schemaName = "writing_independent_evidence",
                        strict = true,
                        taskName = "LANGUAGE_LEARNING_CURATED_WRITING_FEEDBACK",
                    ))
                    require(evidence.provider == output.provider && evidence.model == output.model) {
                        "WRITING_FEEDBACK_MODEL_CHANGED"
                    }
                    modelResults += evidence
                    val independent = CuratedWritingFeedbackV12Policy.validateCurrentIndependent(
                        evidence.output, claim.answer, claim.requirements, claim.segments,
                    )
                    val first = try {
                        CuratedWritingFeedbackV12Policy.validateCurrentFinal(
                            output.output, independent, context, claim.requirements, claim.segments,
                        )
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                    if (first != null) first else {
                        // 최초 응답 실패의 구조 원인만 제시해 한 번 복구한다. 수정본도 같은 전체 검증기를 통과해야 한다.
                        val issues = CuratedWritingFeedbackV12Policy.currentRepairIssues(
                            output.output, independent, context, claim.requirements, claim.segments,
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
                            responseSchema = CuratedWritingFeedbackV12Policy.repairSchema(
                                context, claim.requirements, claim.segments,
                            ),
                            schemaName = "curated_writing_feedback_repair",
                            strict = true,
                            taskName = "LANGUAGE_LEARNING_CURATED_WRITING_FEEDBACK",
                        ))
                        require(repair.provider == output.provider && repair.model == output.model) {
                            "WRITING_FEEDBACK_MODEL_CHANGED"
                        }
                        modelResults += repair
                        CuratedWritingFeedbackV12Policy.validateCurrentFinal(
                            CuratedWritingFeedbackV12Policy.assembleRepair(repair.output, context, claim.requirements),
                            independent, context, claim.requirements, claim.segments,
                        )
                    }
                }
                else -> error("WRITING_FEEDBACK_POLICY_UNSUPPORTED")
            }
            Validated(request.userId, claim.token, feedback, output.provider, output.model,
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

    private suspend fun serverRepairFeedback(
        claim: Claim,
        plan: CuratedWritingFeedbackV13Policy.Plan,
        initial: ModelExecutionResult,
        results: MutableList<ModelExecutionResult>,
        deadline: CuratedWritingSemanticDeadline,
    ): JsonObject {
        // 호출 수에는 실패한 전송도 포함한다. 필수 복구에 쓴 예산을 선택 제안이 추가로 늘릴 수 없다.
        var attemptedCalls = 1
        suspend fun call(stage: String, instructions: String, prompt: String,
                         schema: JsonObject, schemaName: String): ModelExecutionResult {
            require(attemptedCalls < 5) { "WRITING_FEEDBACK_CALL_LIMIT" }
            attemptedCalls++
            val result = semanticCall(claim, stage, instructions, prompt, schema, schemaName, deadline)
            results += result
            return result
        }

        // 원문 관찰은 기존 계약을 유지하고, 새 근거 검사는 서버가 확인한 실제 문자열 범위만 받는다.
        val extracted = CuratedWritingFeedbackV14Policy.validateExtraction(initial.output, plan)
        val evidence = call("evidence", CuratedWritingEvidenceV14.verificationInstructions,
            CuratedWritingEvidenceV14.verificationPrompt(plan, extracted),
            CuratedWritingEvidenceV14.verificationSchema(plan, extracted), "writing_atom_evidence_v14")
        val verified = CuratedWritingEvidenceV14.validateVerification(evidence.output, plan, extracted)
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)

        // 서버가 소유한 R ID의 전체 적용 문장을 받고 고정 범위 밖 보존을 확인한다. 정상 수리는 고정한다.
        val core = CuratedWritingFeedbackV14Policy.serverCoreIfComplete(plan, verified, repairs) ?: run {
            val composed = call("core", CuratedWritingFeedbackV14Policy.coreInstructions,
                CuratedWritingFeedbackV14Policy.corePrompt(plan, verified, repairs),
                CuratedWritingFeedbackV14Policy.coreSchema(repairs), "writing_server_repairs")
            val progress = CuratedWritingFeedbackV14Policy.salvageCore(plan, verified, repairs, composed.output)
            progress.completedResult ?: run {
                val repaired = call("core-repair", CuratedWritingFeedbackV14Policy.repairRemainingInstructions,
                    CuratedWritingFeedbackV14Policy.repairRemainingPrompt(plan, verified, repairs, progress),
                    CuratedWritingFeedbackV14Policy.repairRemainingSchema(repairs, progress),
                    "writing_server_repair_remaining")
                CuratedWritingFeedbackV14Policy.assembleRepairedCore(plan, verified, repairs, progress, repaired.output)
            }
        }

        // 필수 교정의 전체 문맥 검수는 선택 제안과 분리한다. 실패한 필수 결과를 선택 실패로 저장하지 않는다.
        if (core.getValue("necessaryCorrections").jsonArray.isNotEmpty()) {
            val checkPlan = CuratedWritingCoreVerificationV14.prepare(plan, verified, repairs, core)
            val checked = call("core-verification", CuratedWritingCoreVerificationV14.instructions,
                CuratedWritingCoreVerificationV14.prompt(checkPlan),
                CuratedWritingCoreVerificationV14.schema(checkPlan), "writing_core_verification")
            CuratedWritingCoreVerificationV14.validate(checked.output, checkPlan)
        }

        // 핵심 결과가 전체 검사를 통과한 뒤에만 별도 제안을 만든다. 누락 내용은 대상에 포함하지 않는다.
        val targets = CuratedWritingOptionalSuggestionsV14.planTargets(plan, verified, core)
        val optional = when {
            targets.targets.isEmpty() -> CuratedWritingOptionalSuggestionsV14.Result(
                CuratedWritingOptionalSuggestionsV14.Status.NOT_APPLICABLE, JsonArray(emptyList()), null)
            attemptedCalls + 2 > 5 -> CuratedWritingOptionalSuggestionsV14.Result(
                CuratedWritingOptionalSuggestionsV14.Status.NOT_RUN_LIMIT, JsonArray(emptyList()), "OPTIONAL_CALL_LIMIT")
            deadline.remainingMillis() <= 0 -> CuratedWritingOptionalSuggestionsV14.Result(
                CuratedWritingOptionalSuggestionsV14.Status.NOT_RUN_LIMIT, JsonArray(emptyList()), "OPTIONAL_DEADLINE_LIMIT")
            else -> try {
                val proposal = call("optional-proposal", CuratedWritingOptionalSuggestionsV14.proposalInstructions,
                    CuratedWritingOptionalSuggestionsV14.proposalPrompt(targets),
                    CuratedWritingOptionalSuggestionsV14.proposalSchema(targets), "writing_optional_proposals")
                val checked = CuratedWritingOptionalSuggestionsV14.validateProposals(proposal.output, targets)
                if (checked.getValue("proposals").jsonArray.isEmpty()) {
                    CuratedWritingOptionalSuggestionsV14.Result(
                        CuratedWritingOptionalSuggestionsV14.Status.SUCCEEDED, JsonArray(emptyList()), null)
                } else {
                    val decision = call("optional-verification", CuratedWritingOptionalSuggestionsV14.verificationInstructions,
                        CuratedWritingOptionalSuggestionsV14.verificationPrompt(targets, checked),
                        CuratedWritingOptionalSuggestionsV14.verificationSchema(targets, checked),
                        "writing_optional_verification")
                    CuratedWritingOptionalSuggestionsV14.validateVerifiedSuggestions(decision.output, targets, checked)
                }
            } catch (failure: ModelExecutionFailure) {
                // 제안 호출 실패는 이미 검증한 필수 결과를 버리지 않는다. 자동 재시도는 실행하지 않는다.
                CuratedWritingOptionalSuggestionsV14.Result(
                    CuratedWritingOptionalSuggestionsV14.Status.FAILED, JsonArray(emptyList()), "OPTIONAL_MODEL_FAILED")
            } catch (failure: IllegalArgumentException) {
                // 서비스 모델 변경은 선택 제안 오류로 숨기지 않고 전체 요청의 기존 실패 경로로 보낸다.
                if (failure.message == "WRITING_FEEDBACK_MODEL_CHANGED") throw failure
                CuratedWritingOptionalSuggestionsV14.Result(
                    CuratedWritingOptionalSuggestionsV14.Status.FAILED, JsonArray(emptyList()), "OPTIONAL_VALIDATION_FAILED")
            }
        }
        return CuratedWritingFeedbackV14Policy.withOptionalResult(plan, verified, repairs, core,
            optional.rows, optional.status.name, optional.failureCode)
    }

    private suspend fun semanticFeedback(
        claim: Claim,
        plan: CuratedWritingFeedbackV13Policy.Plan,
        initial: ModelExecutionResult,
        results: MutableList<ModelExecutionResult>,
        deadline: CuratedWritingSemanticDeadline,
    ): JsonObject {
        // 관찰 단계에는 판정이 없다. 독립 연결 검사가 원문과 고정 의미 단위를 대조한 뒤 근거를 확정한다.
        val extracted = CuratedWritingFeedbackV13Policy.validateExtraction(initial.output, plan)
        val evidence = semanticCall(claim, "evidence", CuratedWritingFeedbackV13Policy.verificationInstructions,
            CuratedWritingFeedbackV13Policy.verificationPrompt(plan, extracted),
            CuratedWritingFeedbackV13Policy.verificationSchema(plan), "writing_atom_evidence", deadline)
        results += evidence
        val verified = CuratedWritingFeedbackV13Policy.validateVerification(evidence.output, plan)

        // 교정·설명 모델에는 확정 근거를 제공하지만 그 상태나 원문 경계를 수정할 응답 필드를 주지 않는다.
        val composed = semanticCall(claim, "compose", CuratedWritingFeedbackV13Policy.compositionInstructions,
            CuratedWritingFeedbackV13Policy.compositionPrompt(plan, verified),
            CuratedWritingFeedbackV13Policy.compositionSchema(plan, verified), "writing_local_corrections", deadline)
        results += composed
        try {
            return CuratedWritingFeedbackV13Policy.assemble(composed.output, plan, verified)
        } catch (_: IllegalArgumentException) {
            // 유효한 교정과 의미 근거는 고정하고 실패한 교정 위치만 한 번 복구한다. 의미 실패는 이 경로에 오지 않는다.
            val issues = CuratedWritingFeedbackV13Policy.issues(composed.output, plan, verified)
            val repaired = semanticCall(claim, "repair", CuratedWritingFeedbackV13Policy.localRepairInstructions,
                CuratedWritingFeedbackV13Policy.localRepairPrompt(plan, verified, composed.output, issues),
                CuratedWritingFeedbackV13Policy.localRepairSchema(plan, verified, composed.output),
                "writing_local_correction_repair", deadline)
            results += repaired
            return CuratedWritingFeedbackV13Policy.assembleLocalRepair(repaired.output, plan, verified, composed.output)
        }
    }

    private suspend fun semanticCall(
        claim: Claim,
        stage: String,
        instructions: String,
        prompt: String,
        schema: JsonObject,
        schemaName: String,
        deadline: CuratedWritingSemanticDeadline,
    ): ModelExecutionResult {
        val result = deadline.execute(model, ModelExecutionCommand(
            traceId = "curated-feedback-${claim.answerId}-${claim.attempt}-$stage",
            instructions = instructions,
            messages = listOf(ModelMessage("user", prompt)),
            tier = ModelTier.MINI,
            maxOutputTokens = 4096,
            deadlineUtc = clock.instant().plusSeconds(90),
            responseSchema = schema,
            schemaName = schemaName,
            strict = true,
            taskName = "LANGUAGE_LEARNING_CURATED_WRITING_FEEDBACK",
        ))
        require(result.provider == "openai" && result.model in setOf("gpt-5-mini", "gpt-5-mini-2025-08-07")) {
            "WRITING_FEEDBACK_MODEL_CHANGED"
        }
        return result
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
            // 저장된 과제 내용에 등록된 의미 계약이 없으면 기존 정책을 보존한다. 답안에 맞춰 계약을 만들지 않는다.
            val context = CuratedWritingFeedbackPolicy.Context(WritingType.valueOf(set[CuratedSetsTable.writingType]),
                public, set[CuratedSetsTable.originLanguage], set[CuratedSetsTable.learningLanguage])
            val meaningPlan = CuratedWritingFeedbackV13Policy.prepare(context, requirements, segments)
            val newPolicy = if (meaningPlan != null)
                CuratedWritingFeedbackV14Policy.version else CuratedWritingFeedbackV12Policy.version
            val meaningSuffix = meaningPlan?.let {
                "|${it.contract.sha256()}|${CuratedWritingFeedbackV14Policy.repairPlanVersion}"
            }.orEmpty()
            val requestHash = CuratedWritingManifestCodec.sha256(
                "${request.userId}|$answerId|$answerHash|$sourceHash|$newPolicy$meaningSuffix",
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
                    it[policyVersion] = newPolicy
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
            // V13 재시도는 저작 계약도 identity에 포함한다. 성공본은 당시 저장한 계약 hash로 확인한다.
            val storedMeaningSuffix = if (storedPolicy in setOf(CuratedWritingFeedbackV13Policy.version,
                    CuratedWritingFeedbackV14Policy.version)) {
                val savedHash = if (current[CuratedWritingFeedbackTable.status] == "SUCCEEDED")
                    current[CuratedWritingFeedbackTable.resultJson]?.let { payload ->
                        Json.parseToJsonElement(payload).jsonObject["meaningContractHash"]?.jsonPrimitive?.content
                    } else meaningPlan?.contract?.sha256()
                require(savedHash != null && savedHash.matches(Regex("[a-f0-9]{64}"))) { "WRITING_MEANING_CONTRACT_STALE" }
                val repairSuffix = if (storedPolicy == CuratedWritingFeedbackV14Policy.version) {
                    val savedVersion = if (current[CuratedWritingFeedbackTable.status] == "SUCCEEDED")
                        current[CuratedWritingFeedbackTable.resultJson]?.let { payload ->
                            Json.parseToJsonElement(payload).jsonObject["repairPlanVersion"]?.jsonPrimitive?.content
                        } else CuratedWritingFeedbackV14Policy.repairPlanVersion
                    require(savedVersion == CuratedWritingFeedbackV14Policy.repairPlanVersion) {
                        "WRITING_REPAIR_CONTRACT_STALE"
                    }
                    "|$savedVersion"
                } else ""
                "|$savedHash$repairSuffix"
            } else ""
            val storedRequestHash = CuratedWritingManifestCodec.sha256(
                "${request.userId}|$answerId|$answerHash|$sourceHash|$storedPolicy$storedMeaningSuffix",
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
                it[claimUntil] = now.plusSeconds(when (storedPolicy) {
                    CuratedWritingFeedbackV13Policy.version, CuratedWritingFeedbackV14Policy.version -> 450
                    CuratedWritingFeedbackV12Policy.version -> 330
                    else -> 180
                })
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
        // 타인의 재시도가 검증 완료된 소유자의 무과금 저장 복구 결과를 삭제하지 못하게 한다.
        check(result.userId == userId) { "WRITING_ANSWER_NOT_FOUND" }
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
        CuratedWritingFeedbackV12Policy.version, CuratedWritingFeedbackV13Policy.version,
        CuratedWritingFeedbackV14Policy.version)
}

/** 의미 계약의 모델 전체 예산은 단조 시간으로 측정하며, 각 호출은 90초와 남은 시간 중 작은 값을 쓴다. */
internal class CuratedWritingSemanticDeadline(
    private val clock: Clock,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val startedAt = nanoTime()

    data class Allocation(val deadlineUtc: Instant, val timeoutMillis: Long)

    fun remainingMillis(): Long {
        val elapsedNanos = (nanoTime() - startedAt).coerceAtLeast(0)
        return (240_000_000_000L - elapsedNanos) / 1_000_000L
    }

    fun allocate(): Allocation {
        // 벽시계 보정은 경과 시간에 영향을 주지 않는다. 전송용 UTC deadline만 현재 시각으로 계산한다.
        val remainingMillis = remainingMillis()
        if (remainingMillis <= 0) throw ModelExecutionFailure("WRITING_FEEDBACK_TOTAL_DEADLINE_EXCEEDED", 504, false)
        val timeoutMillis = minOf(90_000L, remainingMillis)
        return Allocation(clock.instant().plusMillis(timeoutMillis), timeoutMillis)
    }

    suspend fun execute(model: ModelExecutionPort, command: ModelExecutionCommand): ModelExecutionResult {
        // 예산 소진은 외부 호출 전에 중단한다. 전송 후 제한 초과는 기존 UNCERTAIN 저장 경로로 보낸다.
        val allocation = allocate()
        return try {
            withTimeout(allocation.timeoutMillis) {
                model.execute(command.copy(deadlineUtc = allocation.deadlineUtc))
            }
        } catch (_: TimeoutCancellationException) {
            // 이 호출의 제한만 모델 실패다. 상위 HTTP/작업 취소는 선택 제안 실패로 바꾸지 않고 전파한다.
            currentCoroutineContext().ensureActive()
            throw ModelExecutionFailure("MODEL_DEADLINE_EXCEEDED", 504, false)
        }
    }
}
