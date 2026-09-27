package jp.co.translacat.languagelearning.features.speaking.application

import jp.co.translacat.languagelearning.features.growth.application.GrowthProjector
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthChange
import jp.co.translacat.languagelearning.features.speaking.domain.ConversationStartMode
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingAudioRecord
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingFailure
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingSessionRecord
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingConversationExecution
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingSpeechExecution
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingStageFailure
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.Clock
import java.util.*

internal data class SpeakingOpeningClaim(val session: SpeakingSessionRecord, val token: String, val request: JsonObject)

internal class SpeakingOpeningState(private val work: SpeakingUnitOfWork) {
    suspend fun claim(userId: Long, sessionId: Long): SpeakingOpeningClaim? = work.write(userId) {
        val session = records.session(userId, sessionId) ?: throw SpeakingFailure("SESSION_NOT_FOUND")
        if (session.openingReady) return@write null
        if (session.openingState == "RUNNING" && session.openingLeaseAlive(nowUtc)) return@write null
        val other = records.active(userId)
        if (other != null && other.id != sessionId && (other.active || other.openingLeaseAlive(nowUtc))) {
            throw SpeakingFailure("SESSION_NOT_ACTIVE")
        }

        // 기존 opening 최대 실행 예산 안에 소유권을 잡고 늦은 프로세스의 결과를 fence한다.
        val token = UUID.randomUUID().toString()
        val value = records.saveSession(
            session.copy(
                opening = buildJsonObject {
                    put("_executionState", "RUNNING"); put("_token", token)
                    put("_leaseUntil", nowUtc.plusSeconds(180).toString())
                },
            ),
        )
        SpeakingOpeningClaim(value, token, speakingConversationRequest(value))
    }

    suspend fun publish(claim: SpeakingOpeningClaim, response: JsonObject, audio: SpeakingAudioRecord?): Boolean =
        work.write(claim.session.userId) {
            val session = records.session(claim.session.userId, claim.session.id) ?: return@write false
            if (!owns(session, claim)) return@write false
            val conversation = response["conversation"] as? JsonObject
            val resolved = (conversation?.get("resolvedTopic") as? JsonPrimitive)?.contentOrNull
            val snapshot = if (session.snapshot.topicCategory == "KEYWORDS" && !resolved.isNullOrBlank())
                session.snapshot.copy(topicTitle = resolved.trim().take(500)) else session.snapshot

            // 공개 세션·사용량·키워드 선택 기록을 함께 확정해 시작 실패가 일일 집계에 남지 않게 한다.
            if (audio != null) {
                check(audio.sessionId == session.id && audio.turnId == null && audio.role == "OPENING")
                records.saveAudio(audio)
            }
            val usage = (response["usage"] as? JsonObject) ?: JsonObject(emptyMap())
            val keywords = snapshot.selectedKeywords.map { it.getValue("canonicalKey").jsonPrimitive.content }
            if (keywords.isNotEmpty()) GrowthProjector(growth).apply(
                session.userId,
                GrowthChange.KeywordsSelected(session.learningDate, keywords), nowUtc,
            )
            records.addUsage(session.id, null, usage, 0, nowUtc)
            records.saveSession(
                session.copy(
                    snapshot = snapshot, opening = JsonObject(response + ("_executionState" to JsonPrimitive("READY"))),
                    sessionSummary = (conversation?.get("sessionSummary") as? JsonPrimitive)?.contentOrNull,
                    usageSummary = usage, lastActivityAt = nowUtc,
                ),
            )
            true
        }

    suspend fun fail(claim: SpeakingOpeningClaim, code: String): Boolean = work.write(claim.session.userId) {
        val session = records.session(claim.session.userId, claim.session.id) ?: return@write false
        if (!owns(session, claim)) return@write false
        records.saveSession(
            session.copy(
                opening = buildJsonObject {
                    put("_executionState", "FAILED"); put("_failureCode", code)
                },
            ),
        )
        true
    }

    private fun owns(session: SpeakingSessionRecord, claim: SpeakingOpeningClaim) = session.openingState == "RUNNING" &&
        (session.opening["_token"] as? JsonPrimitive)?.contentOrNull == claim.token
}

internal class SpeakingOpeningExecution(
    private val work: SpeakingUnitOfWork,
    private val state: SpeakingOpeningState,
    private val conversation: SpeakingConversationExecution,
    private val speech: SpeakingSpeechExecution,
    private val audioStore: SpeakingAudioStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun start(session: SpeakingSessionRecord): SpeakingSessionRecord {
        val deadline = clock.instant().plusSeconds(180)
        var claim: SpeakingOpeningClaim
        while (true) {
            val current =
                work.read { records.session(session.userId, session.id) } ?: throw SpeakingFailure("SESSION_NOT_FOUND")
            if (current.openingReady) return current
            val claimed = state.claim(session.userId, session.id)
            if (claimed != null) {
                claim = claimed; break
            }

            // 동시 동일 키는 새 모델 호출 대신 진행 중인 소유자의 확정 결과를 기다린다.
            if (!clock.instant().isBefore(deadline)) throw SpeakingFailure("PROVIDER_TIMEOUT")
            delay(25)
        }
        try {
            val snapshot = claim.session.snapshot
            val response = if (snapshot.resolvedStartMode == ConversationStartMode.USER_FIRST) buildJsonObject {
                put("assistantText", JsonNull); put("conversation", JsonNull); put("usage", JsonObject(emptyMap()))
            } else conversation.generate(claim.request, deadline)
            var audio: SpeakingAudioRecord? = null
            var usage = response["usage"] as? JsonObject ?: JsonObject(emptyMap())
            val text = (response["assistantText"] as? JsonPrimitive)?.contentOrNull
            if (text != null) {
                try {
                    val synthesized = speech.synthesize(
                        text, claim.request.getValue("requestId").jsonPrimitive.content,
                        snapshot.learningLanguage, snapshot.voiceId, snapshot.playbackSpeed, snapshot.policy,
                    )
                    usage = mergeSpeakingUsage(usage, synthesized.usage)
                    audio = work.read {
                        speakingAudioRecord(
                            claim.session, null, 0, "OPENING", synthesized.bytes, "audio/wav", null,
                        )
                    }
                    audioStore.put(audio.objectKey, synthesized.bytes, audio.contentType)
                } catch (_: SpeakingStageFailure) {
                    // 원본은 opening TTS 실패에서도 검증된 시작 문장을 제공한다.
                }
            }
            if (!state.publish(claim, JsonObject(response + ("usage" to usage)), audio)) {
                throw SpeakingFailure("SESSION_NOT_ACTIVE")
            }
            return checkNotNull(work.read { records.session(session.userId, session.id) })
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { state.fail(claim, "EXECUTION_CANCELLED") }
            throw cancelled
        } catch (failure: SpeakingStageFailure) {
            state.fail(claim, failure.code)
            throw SpeakingFailure(failure.code)
        } catch (failure: ModelExecutionFailure) {
            state.fail(claim, failure.code)
            throw failure
        } catch (failure: Exception) {
            state.fail(claim, "OPENING_EXECUTION_FAILED")
            throw failure
        }
    }
}
