package jp.co.translacat.languagelearning.features.speaking.application

import jp.co.translacat.languagelearning.features.speaking.domain.*
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** 제출 시점의 발화·revision·오디오 근거를 고정하고 수동 재시도에서는 다시 만들지 않는다. */
internal fun SpeakingTransaction.speakingEvaluationRequest(
    session: SpeakingSessionRecord, problemIndex: Int,
): JsonObject {
    val all = records.turns(session.id)
    val snapshot = session.snapshot
    val problem = problemIndex > 0
    val turns = if (problem) all.filter { it.problemIndex == problemIndex && !it.excludedFromEvaluation } else all
    val users = JsonArray(turns.map { turn -> evaluationUser(turn) })
    val sourceHash = sha256(users.toString())
    val fingerprint = sha256(turns.joinToString("|") { "${it.id}:${it.recordingRevision}" }).take(16)
    val coaching = snapshot.resultKind == SpeakingResultKind.SESSION_COACHING
    val policy = "speaking-evaluation-policy-v2"
    val requestId = when {
        problem -> "speaking-read-aloud-evaluation-${session.id}-$problemIndex-$fingerprint"
        coaching -> "speaking-coaching-${session.id}-retry-0"
        else -> "speaking-evaluation-${session.id}-retry-0"
    }
    val idempotency = when {
        problem -> "speaking-read-aloud-evaluation:${session.id}:$problemIndex:$fingerprint"
        coaching -> "speaking-coaching:${session.id}:${snapshot.resultPolicyVersion}:$sourceHash"
        else -> "speaking-evaluation:${session.id}:$policy"
    }

    // READ_ALOUD는 해당 문제의 참조문장만 assistant evidence로 보낸다.
    val assistants = if (problem) {
        val script = if (problemIndex == 1) session.opening["assistantText"]?.jsonPrimitive?.contentOrNull
        else all.filter { it.problemIndex == problemIndex - 1 }
            .mapNotNull { it.content.assistantText?.takeIf(String::isNotBlank) }
            .lastOrNull()
        if (script.isNullOrBlank()) throw SpeakingFailure("EVALUATION_FAILED")
        listOf(
            assistant(
                "read-aloud-problem-$problemIndex", (problemIndex - 1).coerceAtLeast(0), script,
                buildJsonObject { put("scriptText", script) },
            ),
        )
    } else buildList {
        session.opening["assistantText"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { text ->
            add(assistant("opening", 0, text, session.opening["conversation"] as? JsonObject ?: JsonObject(emptyMap())))
        }
        turns.forEach { turn ->
            turn.content.assistantText?.takeIf(String::isNotBlank)?.let { text ->
                add(assistant(turn.id.toString(), turn.turnIndex, text, turn.content.conversation))
            }
        }
    }
    return buildJsonObject {
        put("requestId", requestId)
        put("idempotencyKey", idempotency)
        put("sessionId", if (problem) "${session.id}:read-aloud:$problemIndex" else session.id.toString())
        put("topic", if (problem) "${snapshot.topicTitle} / Problem $problemIndex" else snapshot.topicTitle)
        put("practiceMode", snapshot.practiceMode.name)
        put("evaluationScope", if (problem) "READ_ALOUD_PROBLEM" else "SESSION")
        put("goal", if (problem) JsonNull else snapshot.goal?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "targetLevel",
            snapshot.topicId?.let { records.topic(it)?.recommendedLevel }?.let(::JsonPrimitive) ?: JsonNull,
        )
        put("originLanguage", snapshot.originLanguage)
        put("learningLanguage", snapshot.learningLanguage)
        put("userTurns", users)
        put("assistantTurns", JsonArray(assistants))
        put("sessionSummary", if (problem) JsonNull else session.sessionSummary?.let(::JsonPrimitive) ?: JsonNull)
        put("priorProfileSummary", snapshot.learningProfile ?: JsonNull)
        put(
            "evaluationPolicyVersion",
            if (problem) "$policy:read-aloud-problem:$problemIndex:evidence:$fingerprint" else policy,
        )
        put("manualRetryAttempt", 0)
        if (coaching) {
            put("resultKind", snapshot.resultKind.name)
            put("resultPolicyVersion", snapshot.resultPolicyVersion)
            put("sourceSnapshotHash", sourceHash)
        }
    }
}

private fun SpeakingTransaction.evaluationUser(turn: SpeakingTurnRecord): JsonObject {
    val content = turn.content
    val audio = records.audio(turn.sessionId, turn.id, "USER")
    return buildJsonObject {
        put("turnId", turn.id.toString())
        put("turnIndex", turn.turnIndex)
        put("transcript", content.transcript ?: "")
        put("sttConfidence", content.sttConfidence ?: 0.0)
        put("durationSeconds", content.durationSeconds)
        put("segments", JsonArray(content.sttSegments))
        put("audioReference", audio?.objectKey?.let(::JsonPrimitive) ?: JsonNull)
        put("audioAvailable", audio != null)
        put("audioQualitySignals", content.sttMetadata["audioQualitySignals"] ?: JsonNull)
        put("excludedFromEvaluation", turn.excludedFromEvaluation)
        put(
            "assistanceUsage",
            JsonArray(
                SpeakingAssistanceType.entries.mapNotNull { type ->
                    val count = content.assistanceUsage.count { it == type }
                    if (count == 0) null else buildJsonObject { put("type", type.name); put("count", count) }
                },
            ),
        )
        put("sttMetadata", content.sttMetadata.takeUnless { it.isEmpty() } ?: JsonNull)
        put("recordingRevision", turn.recordingRevision)
    }
}

private fun assistant(id: String, index: Int, text: String, conversation: JsonObject) = buildJsonObject {
    put("turnId", id)
    put("turnIndex", index)
    put("text", text)
    put("scriptText", conversation["scriptText"] ?: JsonNull)
    listOf("providedFacts", "requiredIntents", "responseConstraints").forEach { key ->
        put(key, conversation[key]?.takeUnless { it == JsonNull } ?: JsonArray(emptyList()))
    }
}

private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
