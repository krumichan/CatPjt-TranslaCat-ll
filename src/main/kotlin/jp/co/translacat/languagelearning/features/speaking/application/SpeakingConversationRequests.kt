package jp.co.translacat.languagelearning.features.speaking.application

import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingAssistanceType
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingPracticeMode
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingSessionRecord
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingTurnRecord
import kotlinx.serialization.json.*

internal fun SpeakingTransaction.speakingConversationRequest(
    session: SpeakingSessionRecord,
    turn: SpeakingTurnRecord? = null,
    forceStt: Boolean = false,
    durationOverride: Double? = null,
    assistance: List<SpeakingAssistanceType> = turn?.content?.assistanceUsage.orEmpty(),
): JsonObject {
    // 원본의 세션/턴별 멱등 키에 녹음 revision·수동 재시도 번호를 포함한다.
    val snapshot = session.snapshot
    val topic = snapshot.topicId?.let(records::topic)
    val initial = turn == null
    val policy = snapshot.policy
    val profile = snapshot.learningProfile
    val audio = turn?.let { records.audio(session.id, it.id, "USER") }
    val requestId = if (initial) "speaking-session-start-${session.id}"
    else "speaking-turn-${turn!!.id}-recording-${turn.recordingRevision}-retry-${turn.manualRetryCount}"
    val key = if (initial) "${session.createIdempotencyKey}:start"
    else "${turn!!.idempotencyKey}:recording:${turn.recordingRevision}:process:${turn.manualRetryCount}"
    val history = if (initial) emptyList() else records.turns(session.id).filter { it.turnIndex < turn!!.turnIndex }
    return buildJsonObject {
        put("requestId", requestId)
        put("idempotencyKey", key)
        put("sessionId", session.id.toString())
        put("turnIndex", turn?.turnIndex ?: 0)
        put("problemIndex", turn?.problemIndex?.let(::JsonPrimitive) ?: JsonNull)
        put("attemptIndex", turn?.attemptIndex?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "readAloudGenerateNextProblem",
            snapshot.practiceMode == SpeakingPracticeMode.READ_ALOUD &&
                turn?.attemptIndex == 2 && turn.problemIndex != null && turn.problemIndex < 5,
        )
        put("originLanguage", snapshot.originLanguage)
        put("learningLanguage", snapshot.learningLanguage)
        put("topic", snapshot.topicTitle)
        put("practiceMode", snapshot.practiceMode.name)
        put("category", snapshot.topicCategory)
        put("goal", snapshot.goal?.let(::JsonPrimitive) ?: JsonNull)
        put("persona", snapshot.persona?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "conversationStartMode",
            if (initial) snapshot.resolvedStartMode.name else snapshot.conversationStartMode.name,
        )
        put("topicRecommendedStartMode", topic?.recommendedStartMode?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("correctionMode", snapshot.correctionMode.name)
        put("targetLevel", topic?.recommendedLevel?.let(::JsonPrimitive) ?: JsonNull)
        put("learningProfileSummary", profile ?: JsonNull)
        put("selectedKeywords", JsonArray(snapshot.selectedKeywords))
        put("focusSignals", profile?.get("recommendedFocus") ?: JsonArray(emptyList()))
        put(
            "conversationHistory",
            JsonArray(
                history.flatMap { previous ->
                    buildList {
                        previous.content.transcript?.takeIf(String::isNotBlank)
                            ?.let { add(message("USER", it, previous.id)) }
                        previous.content.assistantText?.takeIf(String::isNotBlank)
                            ?.let { add(message("ASSISTANT", it, previous.id)) }
                    }
                },
            ),
        )
        put(
            "assistanceUsage",
            JsonArray(
                SpeakingAssistanceType.entries.mapNotNull { type ->
                    val count = assistance.count { it == type }
                    if (count == 0) null else buildJsonObject { put("type", type.name); put("count", count) }
                },
            ),
        )
        put("sessionSummary", if (initial) JsonNull else session.sessionSummary?.let(::JsonPrimitive) ?: JsonNull)
        put("sessionElapsedSeconds", if (initial) 0 else session.totalDurationSeconds)
        put(
            "sessionPolicySnapshot",
            buildJsonObject {
                put("maxSessionMinutes", policy.maxSessionMinutes)
                put("maxTurns", snapshot.maxTurns)
                put("minValidAudioSeconds", policy.minValidAudioSeconds)
                put("maxTurnAudioSeconds", policy.maxTurnAudioSeconds)
                put("maxAudioFileBytes", policy.maxAudioFileBytes)
                put("automaticRetryLimitPerStage", policy.automaticRetryLimitPerStage)
                put("manualRetryLimitPerStage", policy.manualRetryLimitPerStage)
            },
        )
        put("audioReference", audio?.objectKey?.let(::JsonPrimitive) ?: JsonNull)
        put("audioFormat", audio?.contentType?.let(::JsonPrimitive) ?: JsonNull)
        put("durationSeconds", (durationOverride ?: turn?.content?.durationSeconds)?.let(::JsonPrimitive) ?: JsonNull)
        put("voice", snapshot.voiceId)
        put("playbackSpeed", snapshot.playbackSpeed)
        put("manualRetryAttempt", turn?.manualRetryCount ?: 0)
        put(
            "transcript",
            if (forceStt || turn?.content?.transcript.isNullOrBlank()) JsonNull
            else existingTranscript(checkNotNull(turn), snapshot.learningLanguage),
        )
        put("isInitialTurn", initial || turn?.turnIndex == 1)
    }
}

private fun message(role: String, text: String, turnId: Long) = buildJsonObject {
    put("role", role)
    put("text", text)
    put("turnId", turnId.toString())
}

private fun existingTranscript(turn: SpeakingTurnRecord, language: String) = buildJsonObject {
    put("text", turn.content.transcript)
    put("language", language)
    put("confidence", turn.content.sttConfidence ?: 0.0)
    // 원본 Core가 저장된 STT를 복원할 때 쓰던 0.5 기준을 신규 STT의 0.55와 혼합하지 않는다.
    put("isLowConfidence", turn.content.sttConfidence == null || turn.content.sttConfidence < .5)
    put("segments", JsonArray(turn.content.sttSegments))
    put("metadata", turn.content.sttMetadata)
}
