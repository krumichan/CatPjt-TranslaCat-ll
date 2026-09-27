package jp.co.translacat.languagelearning.features.listening.application

import jp.co.translacat.languagelearning.features.listening.domain.model.*
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningComprehension
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningDictation
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningProtocolFailure
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionFailure
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.*

internal class ListeningEvaluationWorker(
    private val work: ListeningUnitOfWork,
    private val settings: SettingsServiceOperations,
    private val semantic: ListeningEvaluationExecution,
    private val repeat: ListeningRepeatExecution,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun process(pending: ListeningJob) {
        require(pending.type == "EVALUATE")
        val claim = work.write(pending.userId) {
            claim(pending.userId, pending.id, UUID.randomUUID().toString(), nowUtc.plusMinutes(5))
        } ?: return
        val policy = settings.listeningPolicy()
        val lease = ListeningLease.start(work, claim)
        try {
            val snapshot = work.read {
                val session = checkNotNull(session(claim.userId, claim.aggregateId))
                val attempt = session.attempts.single { it.id == claim.payload.number("attemptId") }
                val response = attempt.tasks.single { it.id == claim.payload.number("responseId") }
                val set = checkNotNull(set(claim.userId, session.setId))
                val item = set.items.single { it.id == attempt.itemId }
                Snapshot(set, attempt, response, item, response.audioId?.let { audio(claim.userId, it)?.bytes })
            }
            if (snapshot.response.status != "EVALUATING" || snapshot.response.revision != claim.payload.number(
                    "revision",
                )
            ) {
                work.write(claim.userId) { finish(claim) }
                return
            }

            // 외부 실행은 잠금 밖에서 수행하며 source/답변 revision을 함께 고정한다.
            val result = evaluate(claim, snapshot, policy, clock.instant().plusSeconds(300))
            work.write(claim.userId) {
                if (!finish(claim)) return@write
                val session = checkNotNull(session(claim.userId, claim.aggregateId))
                val attempt = session.attempts.single { it.id == snapshot.attempt.id }
                val response = attempt.tasks.single { it.id == snapshot.response.id }
                if (response.status != "EVALUATING" || response.revision != snapshot.response.revision ||
                    response.manualRetries != snapshot.response.manualRetries || response.answerText != snapshot.response.answerText ||
                    response.audioId != snapshot.response.audioId
                ) return@write
                val changed = response.copy(
                    status = if (result.evaluable) "EVALUATED" else "NOT_EVALUABLE",
                    evaluation = result, evaluationHistory = response.evaluationHistory + result,
                    evaluationRecords = response.evaluationRecords + ListeningEvaluationRecord(
                        allocateId(), nowUtc.toString(), result,
                    ),
                    evaluatedAt = nowUtc.toString(), evaluationErrorCode = null,
                )
                ListeningProfiles.record(this, snapshot.set, attempt, response, result, policy.profilePolicyVersion)
                val updated = session.copy(
                    attempts = session.attempts.map { old ->
                        if (old.id != attempt.id) old else old.copy(
                            tasks = old.tasks.map { if (it.id == changed.id) changed else it },
                        )
                    },
                )
                updateSession(ListeningAttemptFinalizer.finalize(this, updated, attempt.id))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ModelExecutionFailure) {
            failure(claim, policy, failure.code, failure.retryable, Duration.ofSeconds(failure.retryAfterSeconds ?: 1))
        } catch (failure: ListeningProtocolFailure) {
            failure(claim, policy, failure.code, false, Duration.ZERO)
        } catch (_: Exception) {
            failure(claim, policy, "AI_EVALUATION_FAILED", false, Duration.ZERO)
        } finally {
            lease.close()
        }
    }

    private suspend fun evaluate(
        claim: ListeningJob, snapshot: Snapshot, policy: ListeningPolicy, deadline: Instant,
    ): ListeningTaskResult {
        val (set, attempt, response, item, audio) = snapshot
        val source = item.content.getValue("sourceText").jsonPrimitive.content
        val context =
            ListeningEvaluationContext(attempt.purpose == "OFFICIAL", attempt.answerRevealed, response.assistanceUsage)
        return when (response.taskType) {
            ListeningTaskType.DICTATION -> ListeningDictation.evaluate(
                source, checkNotNull(response.answerText), set.learningLanguage, context = context,
            )

            ListeningTaskType.COMPREHENSION -> {
                val options = item.content.getValue("options").jsonArray.map { it.jsonObject }
                val mapped = options.associate {
                    it.getValue("key").jsonPrimitive.content to it.getValue(
                        "text",
                    ).jsonPrimitive.content
                }
                if (mapped.size != options.size) throw ListeningProtocolFailure()
                ListeningComprehension.evaluate(
                    mapped, checkNotNull(response.answerText),
                    item.content.getValue("correctOptionKey").jsonPrimitive.content, set.originLanguage, context,
                )
            }

            ListeningTaskType.REPEAT_AFTER_AUDIO -> repeat.evaluate(
                checkNotNull(audio), source,
                item.audioDurationMs?.div(1000.0) ?: item.content.getValue(
                    "estimatedAudioSeconds",
                ).jsonPrimitive.double,
                set.learningLanguage, item.content.strings("keyMeaningUnits"), "ll-listening-evaluation-${claim.id}",
                deadline, context,
            )

            ListeningTaskType.INTERPRETATION, ListeningTaskType.SUMMARY -> {
                val request = buildJsonObject {
                    put("requestId", "ll-listening-evaluation-${claim.id}")
                    put("idempotencyKey", claim.key)
                    put("itemId", item.id)
                    put("attemptId", attempt.id)
                    put("evaluationPurpose", attempt.purpose)
                    put("answerRevealed", attempt.answerRevealed)
                    put(
                        "assistanceUsage",
                        JsonArray(
                            response.assistanceUsage.map { usage ->
                                buildJsonObject {
                                    put("type", usage.type); put(
                                    "count", usage.count,
                                )
                                }
                            },
                        ),
                    )
                    put("policyVersion", policy.profilePolicyVersion)
                    put("modelConfigVersion", policy.modelConfigVersion)
                    put("manualRetryAttempt", response.manualRetries)
                    put("sourceText", source)
                    put("answer", checkNotNull(response.answerText))
                    put("originLanguage", set.originLanguage)
                    put("learningLanguage", set.learningLanguage)
                    if (response.taskType == ListeningTaskType.INTERPRETATION) {
                        put("referenceMeanings", item.content.getValue("referenceMeanings"))
                        put("keyMeaningUnits", item.content.getValue("keyMeaningUnits"))
                    } else put("summaryKeyPoints", item.content.getValue("summaryKeyPoints"))
                }
                semantic.evaluate(response.taskType, request, context, deadline)
            }
        }
    }

    private suspend fun failure(
        claim: ListeningJob, policy: ListeningPolicy, code: String, retryable: Boolean, retryAfter: Duration,
    ) {
        work.write(claim.userId) {
            val decision = fail(claim, code, retryable, policy.automaticRetryLimit, retryAfter)
            if (decision == ListeningJobFailure.STALE) return@write
            val session = checkNotNull(session(claim.userId, claim.aggregateId))
            val attemptId = claim.payload.number("attemptId")
            val responseId = claim.payload.number("responseId")
            val attempt = session.attempts.single { it.id == attemptId }
            val updated = session.copy(
                attempts = session.attempts.map { old ->
                    if (old.id != attempt.id) old else old.copy(
                        tasks = old.tasks.map { response ->
                            if (response.id != responseId || response.revision != claim.payload.number(
                                    "revision",
                                ) || response.status != "EVALUATING"
                            ) response
                            else response.copy(
                                status = if (decision == ListeningJobFailure.EXHAUSTED) "EVALUATION_FAILED" else response.status,
                                evaluationErrorCode = code,
                            )
                        },
                    )
                },
            )
            // 기술 실패도 terminal 조건에서 완료되지만 기존 Progress 정책상 학습 실적으로 세지 않는다.
            updateSession(
                if (decision == ListeningJobFailure.EXHAUSTED) ListeningAttemptFinalizer.finalize(
                    this, updated, attempt.id,
                ) else updated,
            )
        }
    }

    private data class Snapshot(
        val set: ListeningSetState, val attempt: ListeningAttemptState,
        val response: ListeningResponseState, val item: ListeningItemState, val audio: ByteArray?,
    )

    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }
}
