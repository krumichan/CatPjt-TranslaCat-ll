package jp.co.translacat.languagelearning.features.speaking.application

import jp.co.translacat.languagelearning.features.speaking.domain.*
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingConversationExecution
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingSpeechExecution
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingStageFailure
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.*

@Serializable
internal data class SpeakingProcessRequest(
    val turnId: Long?,
    val uploadToken: String?,
    val durationSeconds: Double?,
    val assistanceUsage: List<SpeakingAssistanceType>? = null,
    val rerecord: Boolean = false,
)

internal class SpeakingTurnExecution(
    private val work: SpeakingUnitOfWork,
    private val state: SpeakingTurnState,
    private val sessions: SpeakingSessionState,
    private val conversation: SpeakingConversationExecution,
    private val speech: SpeakingSpeechExecution,
    private val audioStore: SpeakingAudioStore,
) {
    suspend fun process(
        userId: Long, sessionId: Long, turnId: Long, request: SpeakingProcessRequest,
        bytes: ByteArray, contentType: String?, fileName: String?,
    ) {
        val (session, turn) = owned(userId, sessionId, turnId)
        if (!request.rerecord && turn.status != SpeakingTurnStatus.AWAITING_UPLOAD) return

        // 기존 파일·길이·도움 사용 검사를 실행 소유권 취득 전에 수행한다.
        SpeakingAudioPolicy.validate(bytes, contentType, request.durationSeconds, session.snapshot.policy)
        val assistance = request.assistanceUsage.orEmpty()
        if (session.snapshot.practiceMode == SpeakingPracticeMode.READ_ALOUD && assistance.isNotEmpty()) throw SpeakingFailure(
            "INVALID_AUDIO",
        )
        val duration = checkNotNull(request.durationSeconds)
        val claim = state.claim(
            userId, sessionId, turnId, request.uploadToken, request.rerecord,
            incomingDurationSeconds = duration,
        ) ?: return
        val audio = userAudio(claim, bytes, checkNotNull(contentType), fileName)
        try {
            audioStore.put(audio.objectKey, bytes, audio.contentType)
            if (!claim.rerecord && !state.saveUpload(claim, audio, duration, assistance)) return
            execute(claim, bytes, duration, assistance, if (claim.rerecord) listOf(audio) else emptyList(), false)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { state.fail(claim, "STT", "EXECUTION_CANCELLED") }
            throw cancelled
        } catch (failure: ModelExecutionFailure) {
            state.fail(claim, "STT", failure.code)
            throw failure
        } catch (failure: SpeakingFailure) {
            state.fail(claim, "STT", failure.code)
            throw failure
        } catch (failure: Exception) {
            state.fail(claim, "STT", "TURN_EXECUTION_FAILED")
            throw failure
        }
    }

    suspend fun retry(userId: Long, sessionId: Long, turnId: Long) {
        val claim = state.claim(userId, sessionId, turnId, null, false, manualRetry = true) ?: return
        try {
            // 성공 STT와 assistant text를 재사용하여 실패한 후속 단계만 다시 실행한다.
            if (claim.turn.failedStage == "TTS" && claim.turn.content.assistantText != null) {
                execute(
                    claim, ByteArray(0), claim.turn.content.durationSeconds, claim.turn.content.assistanceUsage,
                    emptyList(), true,
                )
            } else {
                val audio =
                    work.read { records.audio(sessionId, turnId, "USER") } ?: throw SpeakingFailure("INVALID_AUDIO")
                execute(
                    claim, audioStore.load(audio.objectKey), claim.turn.content.durationSeconds,
                    claim.turn.content.assistanceUsage, emptyList(), false,
                )
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { state.fail(claim, "STT", "EXECUTION_CANCELLED") }
            throw cancelled
        } catch (failure: ModelExecutionFailure) {
            state.fail(claim, "STT", failure.code)
            throw failure
        } catch (failure: SpeakingFailure) {
            state.fail(claim, "STT", failure.code)
            throw failure
        } catch (failure: Exception) {
            state.fail(claim, "STT", "TURN_EXECUTION_FAILED")
            throw failure
        }
    }

    private suspend fun execute(
        claim: SpeakingTurnClaim, bytes: ByteArray, duration: Double,
        assistance: List<SpeakingAssistanceType>, userAudio: List<SpeakingAudioRecord>, onlyTts: Boolean,
    ) {
        val request = work.read {
            val turn = records.turn(claim.session.id, claim.turn.id) ?: throw SpeakingFailure("TURN_NOT_FOUND")
            speakingConversationRequest(
                claim.session, turn, forceStt = claim.rerecord, durationOverride = duration, assistance = assistance,
            )
        }
        val requestId = request.getValue("requestId").jsonPrimitive.content
        val snapshot = claim.session.snapshot
        var content = if (onlyTts) claim.turn.content.copy(usage = JsonObject(emptyMap()))
        else SpeakingTurnContent(durationSeconds = duration, assistanceUsage = assistance)
        try {
            if (!onlyTts) {
                // 복원된 transcript가 있어도 새 요청의 오디오 정규화 검사는 생략하지 않는다.
                val normalized = speech.normalize(bytes, requestId, snapshot.policy)
                val savedTranscript = request["transcript"] as? JsonObject
                val transcript = if (savedTranscript != null) savedTranscript else {
                    val stt = speech.transcribe(
                        normalized, requestId, request.getValue("idempotencyKey").jsonPrimitive.content,
                        snapshot.learningLanguage,
                        snapshot.selectedKeywords.map { it.getValue("text").jsonPrimitive.content },
                        snapshot.policy, claim.turn.manualRetryCount,
                    )
                    content = content.copy(usage = mergeSpeakingUsage(content.usage, stt.getValue("usage").jsonObject))
                    stt.getValue("transcript").jsonObject
                }
                content = content.copy(
                    transcript = transcript.getValue("text").jsonPrimitive.content,
                    sttConfidence = transcript.getValue("confidence").jsonPrimitive.double,
                    sttSegments = transcript.getValue("segments").jsonArray.map { it.jsonObject },
                    sttMetadata = transcript.getValue("metadata").jsonObject,
                )
                val stopsAfterStt = snapshot.practiceMode == SpeakingPracticeMode.READ_ALOUD &&
                    !request.getValue("readAloudGenerateNextProblem").jsonPrimitive.boolean
                if (stopsAfterStt) {
                    state.publish(claim, content, null, userAudio)
                    completeIfNeeded(claim, false)
                    return
                }

                // 대화 출력은 Kotlin parser·원본 정책을 통과한 뒤에만 turn 근거에 포함한다.
                val generated = conversation.generate(JsonObject(request + ("transcript" to transcript)))
                content = content.copy(
                    assistantText = generated.getValue("assistantText").jsonPrimitive.content,
                    conversation = generated.getValue("conversation").jsonObject,
                    usage = mergeSpeakingUsage(content.usage, generated.getValue("usage").jsonObject),
                )
            }

            val audio = speech.synthesize(
                checkNotNull(content.assistantText), requestId, snapshot.learningLanguage,
                snapshot.voiceId, snapshot.playbackSpeed, snapshot.policy,
                if (onlyTts) claim.turn.manualRetryCount else 0,
            )
            content = content.copy(usage = mergeSpeakingUsage(content.usage, audio.usage))
            val record = assistantAudio(claim, audio.bytes)
            audioStore.put(record.objectKey, audio.bytes, record.contentType)
            val applied = state.publish(
                claim, content, (content.conversation["sessionSummary"] as? JsonPrimitive)?.contentOrNull,
                userAudio + record,
            )
            if (applied) completeIfNeeded(
                claim, !onlyTts && (content.conversation["shouldEnd"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        } catch (failure: SpeakingStageFailure) {
            // 최초 처리의 부분 실패는 조회 가능한 결과이고, 재녹음 실패는 이전 결과를 보존한 오류다.
            state.partial(claim, content, failure.stage, failure.code, userAudio)
            if (claim.rerecord) throw SpeakingFailure(failure.code)
        }
    }

    private suspend fun completeIfNeeded(claim: SpeakingTurnClaim, requested: Boolean) {
        val value = work.read { records.session(claim.userId, claim.session.id) } ?: return
        if (SpeakingSessionPolicy.shouldComplete(
                value.active, value.snapshot.practiceMode, requested,
                value.completedTurns, value.snapshot.maxTurns, value.totalDurationSeconds, value.snapshot.policy,
            )
        ) {
            sessions.complete(claim.userId, value.id)
        }
    }

    private suspend fun owned(
        userId: Long, sessionId: Long, turnId: Long,
    ): Pair<SpeakingSessionRecord, SpeakingTurnRecord> = work.write(userId) {
        val stored = records.session(userId, sessionId) ?: throw SpeakingFailure("SESSION_NOT_FOUND")
        val session = stored.expireIfNeeded(nowUtc)
        if (stored != session) records.saveSession(session)
        if (!session.active) throw SpeakingFailure("SESSION_NOT_ACTIVE")
        session to (records.turn(sessionId, turnId) ?: throw SpeakingFailure("TURN_NOT_FOUND"))
    }

    private suspend fun userAudio(claim: SpeakingTurnClaim, bytes: ByteArray, contentType: String, fileName: String?) =
        work.read {
            speakingAudioRecord(
                claim.session, claim.turn.id, claim.turn.recordingRevision, "USER", bytes, contentType, fileName,
            )
        }

    private suspend fun assistantAudio(claim: SpeakingTurnClaim, bytes: ByteArray) = work.read {
        speakingAudioRecord(
            claim.session, claim.turn.id, claim.turn.recordingRevision, "ASSISTANT", bytes, "audio/wav", null,
        )
    }
}

internal fun SpeakingTransaction.speakingAudioRecord(
    session: SpeakingSessionRecord, turnId: Long?, revision: Int,
    role: String, bytes: ByteArray, type: String, name: String?,
) =
    SpeakingAudioRecord(
        sessionId = session.id, turnId = turnId, role = role, recordingRevision = revision,
        objectKey = "${UUID.randomUUID()}.audio", contentType = type, fileName = name, byteLength = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        retentionUntil = nowUtc.plusDays(session.snapshot.policy.rawAudioRetentionDays.toLong()),
    )

internal fun mergeSpeakingUsage(left: JsonObject, right: JsonObject) = buildJsonObject {
    listOf("stt", "conversation", "tts", "evaluation").forEach { stage ->
        put(stage, right[stage]?.takeUnless { it == JsonNull } ?: left[stage] ?: JsonNull)
    }
}
