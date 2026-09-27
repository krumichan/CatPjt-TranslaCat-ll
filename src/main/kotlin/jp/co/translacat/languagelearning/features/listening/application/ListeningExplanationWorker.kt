package jp.co.translacat.languagelearning.features.listening.application

import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningJob
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningAssets
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningProtocolFailure
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningSchema
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.shared.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Duration
import java.util.*

internal class ListeningExplanationWorker(
    private val work: ListeningUnitOfWork, private val settings: SettingsServiceOperations,
    private val model: ModelExecutionPort, private val clock: Clock = Clock.systemUTC(),
    private val budget: ListeningExecutionBudget = ListeningExecutionBudget(clock),
) {
    suspend fun process(pending: ListeningJob) {
        require(pending.type == "EXPLANATION")
        val claim = work.write(pending.userId) {
            claim(pending.userId, pending.id, UUID.randomUUID().toString(), nowUtc.plusMinutes(5))
        } ?: return
        val policy = settings.listeningPolicy()
        val lease = ListeningLease.start(work, claim)
        try {
            val language = claim.payload.getValue("learningLanguage").jsonPrimitive.content
            val target = work.read { recommendations(claim.userId, language).single { it.id == claim.aggregateId } }
            val originLanguage = settings.userSnapshot(claim.userId).result.settings.originLanguage
            val request = buildJsonObject {
                put("requestId", "ll-listening-explanation-${claim.id}")
                put("idempotencyKey", claim.key)
                put("explanationType", "RECOMMENDATION")
                put("targetMetric", target.targetMetric)
                put("recommendedActivity", target.recommendedActivity)
                put("recommendedTask", target.recommendedTask)
                put(
                    "evidenceSummary",
                    buildJsonObject {
                        put("sources", claim.payload.getValue("sources"))
                        put("evidenceCount", maxOf(0, claim.payload.getValue("evidenceCount").jsonPrimitive.int))
                        put(
                            "recentAverage",
                            claim.payload.getValue("recentAverage").jsonPrimitive.double.coerceIn(0.0, 100.0),
                        )
                    },
                )
                put("originLanguage", originLanguage)
                put("policyVersion", "listening-recommendation")
                put("modelConfigVersion", policy.modelConfigVersion)
            }

            // 추천 결정 필드는 모델에 위임하지 않고 고정된 설명·CTA Schema만 요청한다.
            val deadline = clock.instant().plusSeconds(300)
            var attempt = 0
            var payload: JsonObject
            while (true) {
                try {
                    val output = budget.call(deadline, 30) { callDeadline ->
                        model.execute(
                            ModelExecutionCommand(
                                request.getValue("requestId").jsonPrimitive.content,
                                ListeningAssets.instructions("explanation"),
                                listOf(
                                    ModelMessage(
                                        "user",
                                        "Explain this already-decided recommendation without changing it.\n\n$request",
                                    ),
                                ),
                                ModelTier.LUNA, 4096, callDeadline, ListeningAssets.schema("explanation"),
                                "RecommendationExplanationPayload",
                                taskName = "LANGUAGE_LEARNING_LISTENING_EXPLANATION",
                            ),
                        )
                    }.output
                    payload = ListeningSchema.decode(output, ListeningAssets.schema("explanation")).jsonObject
                    val explanation = payload.getValue("explanation").jsonPrimitive.content
                    if (explanation.count { it in ".!?。！？" } > 2) throw ListeningProtocolFailure()
                    break
                } catch (failure: ModelExecutionFailure) {
                    if (!failure.retryable || attempt >= 2 || !clock.instant().isBefore(deadline)) throw failure
                    attempt++
                } catch (failure: ListeningProtocolFailure) {
                    if (attempt >= 2 || !clock.instant().isBefore(deadline)) throw failure
                    attempt++
                }
            }

            // 늦은 lease 응답과 그 사이 해제된 추천에는 설명을 덮어쓰지 않는다.
            work.write(claim.userId) {
                if (!finish(claim)) return@write
                val latest = recommendations(claim.userId, language).single { it.id == claim.aggregateId }
                if (latest.status == "ACTIVE") saveRecommendation(
                    latest.copy(
                        reason = payload.getValue("explanation").jsonPrimitive.content,
                        ctaLabel = payload.getValue("ctaLabel").jsonPrimitive.content,
                        explanationVersion = "listening-explanation",
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ModelExecutionFailure) {
            work.write(claim.userId) {
                fail(
                    claim, failure.code, failure.retryable, policy.automaticRetryLimit,
                    Duration.ofSeconds(failure.retryAfterSeconds ?: 1),
                )
            }
        } catch (failure: ListeningProtocolFailure) {
            work.write(claim.userId) { fail(claim, failure.code, true, policy.automaticRetryLimit) }
        } catch (_: Exception) {
            work.write(claim.userId) { fail(claim, "EXPLANATION_FAILED", false, policy.automaticRetryLimit) }
        } finally {
            lease.close()
        }
    }
}
