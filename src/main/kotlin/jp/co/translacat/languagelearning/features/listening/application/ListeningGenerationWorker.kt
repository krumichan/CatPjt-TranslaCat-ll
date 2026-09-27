package jp.co.translacat.languagelearning.features.listening.application

import jp.co.translacat.languagelearning.features.listening.domain.model.*
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningDuration
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningDurationDemand
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningProtocolFailure
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionFailure
import jp.co.translacat.languagelearning.shared.ai.SpeechExecutionPort
import jp.co.translacat.languagelearning.shared.ai.SpeechSynthesisCommand
import jp.co.translacat.languagelearning.shared.ai.SpeechSynthesisResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.*
import kotlin.math.roundToInt

/** 생성·TTS 외부 실행과 사용자 잠금 commit을 분리하는 영속 작업자다. */
internal class ListeningGenerationWorker(
    private val work: ListeningUnitOfWork,
    private val settings: SettingsServiceOperations,
    private val generation: ListeningGenerationExecution,
    private val speech: SpeechExecutionPort,
    private val clock: Clock = Clock.systemUTC(),
    private val budget: ListeningExecutionBudget = ListeningExecutionBudget(clock),
) {
    suspend fun process(pending: ListeningJob) {
        require(pending.type in setOf("GENERATE", "TTS"))
        val claim = work.write(pending.userId) {
            claim(pending.userId, pending.id, UUID.randomUUID().toString(), nowUtc.plusMinutes(5))
        } ?: return
        val policy = settings.listeningPolicy()
        val lease = ListeningLease.start(work, claim)
        try {
            // 네트워크 실행 동안 DB transaction을 보유하지 않고 기존 300초 상위 예산을 적용한다.
            val deadline = clock.instant().plusSeconds(300)
            if (claim.type == "GENERATE") generate(claim, policy, deadline) else synthesize(claim, policy, deadline)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ModelExecutionFailure) {
            recordFailure(
                claim, policy, failure.code, failure.retryable, Duration.ofSeconds(failure.retryAfterSeconds ?: 1),
            )
        } catch (failure: ListeningProtocolFailure) {
            recordFailure(claim, policy, failure.code, false, Duration.ZERO)
        } catch (_: Exception) {
            // 원문·Provider 응답을 기록하지 않고 예기치 않은 업무 오류도 영속 실패로 분리한다.
            recordFailure(claim, policy, "LISTENING_SYSTEM_FAILURE", false, Duration.ZERO)
        } finally {
            lease.close()
        }
    }

    private suspend fun generate(claim: ListeningJob, policy: ListeningPolicy, deadline: java.time.Instant) {
        val request = work.read {
            val set = checkNotNull(set(claim.userId, claim.aggregateId))
            val replacementId = claim.payload.long("replacementForItemId")
            val previous = replacementId?.let { id -> set.items.single { it.id == id } }
            val correction = claim.payload["durationCorrection"] as? JsonObject
            if (correction != null) {
                check(
                    previous != null && previous.status == "NOT_EVALUABLE" && previous.audioId == null &&
                        previous.content.int("qualityCorrectionCount") == 1 &&
                        previous.content["sourceText"] == correction["previousSourceText"] && correction.int(
                        "qualityCorrectionCount",
                    ) == 1,
                )
            }
            check(set.items.size + 1 <= policy.hardItemLimit)
            val recent = sets(claim.userId).filter { it.learningLanguage == set.learningLanguage }
                .flatMap { it.items.asReversed() }.filter { it.id != previous?.id }.take(200)
            val historyContext = fingerprints.context(claim.userId, set.learningLanguage, nowUtc)
            val current = set.items.filter { it.id != previous?.id }.take(40).map { item -> history(item) }
            val oldHash = previous?.content?.get("contentHash")
            val diversity = JsonObject(
                historyContext + mapOf(
                    "currentSession" to JsonArray(current),
                    "sameFeatureRecent" to JsonArray(
                        historyContext.getValue("sameFeatureRecent").jsonArray.filter {
                            correction == null || it.jsonObject["contentHash"] != oldHash
                        },
                    ),
                    "exactContentHashes90d" to JsonArray(
                        historyContext.getValue("exactContentHashes90d").jsonArray.filter {
                            correction == null || it != oldHash
                        },
                    ),
                ),
            )
            val demand = ListeningDuration.demand(
                JsonObject(
                    set.request + ("constraints" to buildJsonObject {
                        put("audioSecondsMin", 1.0)
                        put("audioSecondsMax", policy.referenceAudioMaxSeconds.toDouble())
                    }),
                ),
            )
            JsonObject(
                set.request + buildJsonObject {
                    put("requestId", "ll-listening-generation-${claim.id}")
                    put("idempotencyKey", claim.key)
                    put("manualRetryAttempt", claim.payload.int("manualRetryAttempt") ?: 0)
                    put(
                        "constraints",
                        buildJsonObject {
                            put("audioSecondsMin", demand.minimum)
                            put("audioSecondsMax", demand.maximum)
                            put(
                                "recentContentHashes",
                                JsonArray(recent.map { it.content.getValue("contentHash") }.distinct()),
                            )
                            put(
                                "recentSimilaritySummaries",
                                JsonArray(recent.map { it.content.getValue("similarityKey") }.distinct()),
                            )
                        },
                    )
                    put("policyVersion", policy.profilePolicyVersion)
                    put("modelConfigVersion", policy.modelConfigVersion)
                    put("diversityContext", diversity)
                    put("durationCorrection", correction ?: JsonNull)
                },
            )
        }
        val generated = generation.generate(request, deadline).single()

        // claim fence를 먼저 확인한 같은 commit에서 새 문항·fingerprint·후속 작업을 저장한다.
        work.write(claim.userId) {
            if (!finish(claim)) return@write
            val set = checkNotNull(set(claim.userId, claim.aggregateId))
            val index = checkNotNull(claim.payload.int("logicalItemIndex"))
            val replacement = claim.payload.long("replacementForItemId")?.let { id -> set.items.single { it.id == id } }
            if (replacement == null && set.items.any { it.index == index }) {
                nextMissing(set)
                return@write
            }
            if (replacement != null && (replacement.status != "NOT_EVALUABLE" || replacement.audioId != null)) return@write
            val reserved = if (replacement != null) missingCount(set) else 0
            check(set.items.size + reserved < policy.hardItemLimit)
            val id = allocateId()
            check(
                fingerprints.register(
                    claim.userId, id, set.learningLanguage, generated.getValue("sourceText").jsonPrimitive.content,
                    generated.getValue("diversityMetadata").jsonObject, nowUtc,
                ),
            ) { "LISTENING_DUPLICATE_CONTENT" }
            val item = ListeningItemState(
                id, index, generated, replacementSequence = claim.payload.int("replacementSequence") ?: 0,
            )
            val items = set.items.map { if (it.id == replacement?.id) it.copy(status = "REPLACED") else it } + item
            val updated = updateSet(refresh(set.copy(items = items)))
            enqueue(claim.userId, set.id, "TTS", "listening:item:$id:tts:0", buildJsonObject { put("itemId", id) })
            if (replacement == null) nextMissing(updated)
        }
    }

    private suspend fun synthesize(claim: ListeningJob, policy: ListeningPolicy, deadline: java.time.Instant) {
        val snapshot = work.read { checkNotNull(set(claim.userId, claim.aggregateId)) }
        val item = snapshot.items.single { it.id == claim.payload.long("itemId") }
        if (item.status != "TTS_PENDING" || item.audioId != null) {
            work.write(claim.userId) { finish(claim) }
            return
        }
        val voice = snapshot.request.getValue("referenceVoice").jsonObject
        check(
            voice.getValue("version").jsonPrimitive.content == "openai-speech-v1" &&
                voice.getValue("voiceKey").jsonPrimitive.content in setOf("marin", "cedar"),
        )
        var result: SpeechSynthesisResult? = null

        // 기존 TTS stage 자동 재시도만 사용한다. 호출별 30초와 공통 deadline을 모두 유지한다.
        for (attempt in 0..policy.automaticRetryLimit) {
            try {
                result = budget.call(deadline, 30) { callDeadline ->
                    speech.synthesize(
                        SpeechSynthesisCommand(
                            item.content.getValue("sourceText").jsonPrimitive.content,
                            voice.getValue("voiceKey").jsonPrimitive.content,
                            snapshot.learningLanguage, "NORMAL", callDeadline, "ll-listening-tts-${claim.id}",
                        ),
                    )
                }
                break
            } catch (failure: ModelExecutionFailure) {
                if (!failure.retryable || attempt >= policy.automaticRetryLimit || clock.instant() >= deadline) throw failure
            }
        }
        val audio = checkNotNull(result)
        if (audio.audioBytes.size > policy.maxAudioFileBytes) throw ListeningProtocolFailure("AUDIO_TOO_LARGE")
        val seconds = ListeningDuration.measuredSeconds(audio.audioBytes, audio.contentType)
        val demandJson = item.content.getValue("durationDemand").jsonObject
        val demand = ListeningDurationDemand(
            demandJson.getValue("minSeconds").jsonPrimitive.double,
            demandJson.getValue("maxSeconds").jsonPrimitive.double,
        )
        val durationFailure = when {
            seconds < demand.minimum -> "AUDIO_TOO_SHORT"; seconds > demand.maximum -> "AUDIO_TOO_LONG"; else -> null
        }

        // 이미 채택된 소스나 audio는 늦은 결과로 교체하지 않는다. blob도 이 transaction에서만 추가한다.
        work.write(claim.userId) {
            if (!finish(claim)) return@write
            val current = checkNotNull(set(claim.userId, snapshot.id))
            val latest = current.items.single { it.id == item.id }
            if (latest.status != "TTS_PENDING" || latest.audioId != null || latest.content != item.content || latest.ttsRetries != item.ttsRetries) return@write
            if (durationFailure != null) {
                val canCorrect = latest.replacementSequence == 0 && latest.content.int("qualityCorrectionCount") == 0 &&
                    seconds.isFinite() && seconds > 0 && current.items.size + missingCount(
                    current,
                ) < policy.hardItemLimit
                val failed = latest.copy(
                    status = "NOT_EVALUABLE", errorCode = durationFailure,
                    content = if (canCorrect) JsonObject(
                        latest.content + ("qualityCorrectionCount" to JsonPrimitive(1)),
                    ) else latest.content,
                )
                updateSet(
                    refresh(
                        current.copy(
                            items = current.items.map { if (it.id == latest.id) failed else it },
                            generationFailure = if (canCorrect) current.generationFailure else durationFailure,
                        ),
                    ),
                )
                if (canCorrect) enqueueReplacement(
                    current, failed,
                    buildJsonObject {
                        put("previousSourceText", latest.content.getValue("sourceText"))
                        put("previousMeasuredSeconds", seconds)
                        put("qualityCorrectionCount", 1)
                    },
                )
                return@write
            }
            val audioId = allocateId()
            insertAudio(
                ListeningAudioState(
                    audioId, claim.userId, item.id, latest.ttsRetries.toLong(), audio.contentType,
                    MessageDigest.getInstance("SHA-256")
                        .digest(audio.audioBytes)
                        .joinToString("") { "%02x".format(it) },
                    audio.audioBytes, nowUtc.plusDays(policy.referenceAudioRetentionDays.toLong()), null,
                ),
            )
            val ready = latest.copy(
                status = "READY", audioId = audioId, audioDurationMs = (seconds * 1000).roundToInt(), errorCode = null,
            )
            updateSet(refresh(current.copy(items = current.items.map { if (it.id == latest.id) ready else it })))
        }
    }

    private suspend fun recordFailure(
        claim: ListeningJob, policy: ListeningPolicy, code: String, retryable: Boolean, retryAfter: Duration,
    ) {
        work.write(claim.userId) {
            if (fail(
                    claim, code, retryable, policy.automaticRetryLimit, retryAfter,
                ) != ListeningJobFailure.EXHAUSTED
            ) return@write
            val set = checkNotNull(set(claim.userId, claim.aggregateId))
            if (claim.type == "GENERATE") {
                updateSet(refresh(set.copy(generationFailure = code)))
                return@write
            }
            val item = set.items.single { it.id == claim.payload.long("itemId") }
            if (item.status != "TTS_PENDING") return@write
            val failed = item.copy(status = "NOT_EVALUABLE", errorCode = code)
            val sourceIndependent = code in setOf("PROVIDER_RATE_LIMITED", "PROVIDER_UNAVAILABLE", "PROVIDER_TIMEOUT")
            val canReplace =
                !sourceIndependent && item.replacementSequence < 1 && item.content.int("qualityCorrectionCount") == 0 &&
                    set.items.size + missingCount(set) < policy.hardItemLimit
            updateSet(
                refresh(
                    set.copy(
                        items = set.items.map { if (it.id == item.id) failed else it },
                        generationFailure = if (canReplace) set.generationFailure else code,
                    ),
                ),
            )
            if (canReplace) enqueueReplacement(set, failed, null)
        }
    }

    private fun ListeningTransaction.nextMissing(set: ListeningSetState) {
        val missing = (1..set.targetItemCount).firstOrNull { index -> set.items.none { it.index == index } } ?: return
        enqueue(
            set.userId, set.id, "GENERATE", "listening:set:${set.id}:generate:item:$missing:manual:0",
            buildJsonObject {
                put("logicalItemIndex", missing)
                put("manualRetryAttempt", 0)
                put("replacementSequence", 0)
            },
        )
    }

    private fun ListeningTransaction.enqueueReplacement(
        set: ListeningSetState, item: ListeningItemState, correction: JsonObject?,
    ) {
        val key = if (correction != null) "listening:set:${set.id}:duration-correction:${item.index}"
        else "listening:set:${set.id}:replace:${item.index}:${item.replacementSequence + 1}:tts-manual:${item.ttsRetries}"
        enqueue(
            set.userId, set.id, "GENERATE", key,
            buildJsonObject {
                put("replacementForItemId", item.id)
                put("logicalItemIndex", item.index)
                put("replacementSequence", item.replacementSequence + 1)
                put("manualRetryAttempt", 0)
                put("durationCorrection", correction ?: JsonNull)
            },
        )
    }

    private fun missingCount(set: ListeningSetState) =
        (1..set.targetItemCount).count { index -> set.items.none { it.index == index } }

    private fun history(item: ListeningItemState): JsonObject = JsonObject(
        item.content.getValue("diversityMetadata").jsonObject + buildJsonObject {
            put("sourceType", "LISTENING")
            put("content", item.content.getValue("sourceText"))
            put("contentHash", item.content.getValue("contentHash"))
            put("ageDays", 0)
        },
    )

    private fun refresh(set: ListeningSetState): ListeningSetState {
        if (set.status == "COMPLETED") return set
        val ready = set.items.filter { it.status == "READY" }.map { it.index }.distinct().size
        val pending = set.items.any { it.status == "TTS_PENDING" }
        val status = when {
            ready >= set.targetItemCount -> "READY"
            ready > 0 || pending || set.generationFailure != null && set.items.isNotEmpty() -> "PARTIAL"
            set.generationFailure != null -> "FAILED"
            else -> "GENERATING"
        }
        return set.copy(status = status, generationFailure = if (status == "READY") null else set.generationFailure)
    }

    private fun JsonObject.int(name: String) = (this[name] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(name: String) = (this[name] as? JsonPrimitive)?.longOrNull
}
