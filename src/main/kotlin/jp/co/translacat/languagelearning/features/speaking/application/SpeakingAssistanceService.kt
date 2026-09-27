package jp.co.translacat.languagelearning.features.speaking.application

import jp.co.translacat.languagelearning.features.speaking.domain.*
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingAssistanceExecution
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingStageFailure
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
internal data class SpeakingAssistanceRequest(val type: SpeakingAssistanceType? = null, val targetTurnId: Long? = null)

internal class SpeakingAssistanceService(
    private val work: SpeakingUnitOfWork, private val execution: SpeakingAssistanceExecution,
) {
    suspend fun get(userId: Long, sessionId: Long, input: SpeakingAssistanceRequest): JsonObject {
        val type = input.type ?: throw SpeakingFailure("SPEAKING_ASSISTANCE_FAILED")
        val context = work.write(userId) {
            val stored = records.session(userId, sessionId) ?: throw SpeakingFailure("SESSION_NOT_FOUND")
            val session = stored.expireIfNeeded(nowUtc)
            if (stored != session) records.saveSession(session)
            if (!session.active) throw SpeakingFailure("SESSION_NOT_ACTIVE")
            if (session.snapshot.practiceMode == SpeakingPracticeMode.READ_ALOUD) throw SpeakingFailure(
                "SPEAKING_ASSISTANCE_FAILED",
            )
            val turns = records.turns(sessionId)
            val turn = if (input.targetTurnId != null) records.turn(
                sessionId, LearningPublicId.decode(input.targetTurnId.toString()),
            )
                ?: throw SpeakingFailure(
                    "TURN_NOT_FOUND",
                ) else turns.lastOrNull { !it.content.assistantText.isNullOrBlank() }
            val text =
                turn?.content?.assistantText ?: (session.opening["assistantText"] as? JsonPrimitive)?.contentOrNull
            if (text.isNullOrBlank()) throw SpeakingFailure("SPEAKING_ASSISTANCE_FAILED")
            val index = (session.completedTurns + 1).coerceIn(1, session.snapshot.maxTurns)
            val path = "/api/v1/language-learning/speaking/sessions/${LearningPublicId.encode(session.id)}"
            val audio = records.audio(sessionId, turn?.id, if (turn == null) "OPENING" else "ASSISTANT")
            val audioUrl = if (audio == null) null else if (turn == null) "$path/audio/opening"
            else "$path/turns/${LearningPublicId.encode(turn.id)}/audio"
            AssistanceContext(
                session, turn, index, text, audioUrl, assistanceRequest(session, turn, turns, type, text, index),
            )
        }

        // 재생·속도·질문 보기는 원본과 같이 모델 호출과 사용량 기록 없이 반환한다.
        when (type) {
            SpeakingAssistanceType.REPLAY -> return response(context, type, null, context.audioUrl, 1.0)
            SpeakingAssistanceType.SLOW_PLAYBACK -> return response(context, type, null, context.audioUrl, .75)
            SpeakingAssistanceType.SHOW_QUESTION -> return response(context, type, context.text, null, 1.0)
            else -> Unit
        }
        val generated = try {
            execution.generate(context.request)
        } catch (failure: SpeakingStageFailure) {
            throw SpeakingFailure(failure.code)
        }
        val content = generated.getValue("content").jsonPrimitive.content
        if (generated.getValue("type").jsonPrimitive.content != type.name || content.isBlank()) throw SpeakingFailure(
            "SPEAKING_ASSISTANCE_FAILED",
        )

        // 성공 응답의 캐시 재생은 사용량을 다시 기록하지 않는다.
        if (!generated.getValue("idempotentReplay").jsonPrimitive.boolean) work.write(userId) {
            records.addUsage(sessionId, context.turn?.id, generated.getValue("usage").jsonObject, 0, nowUtc)
        }
        return response(context, type, content, null, 1.0)
    }

    private fun SpeakingTransaction.assistanceRequest(
        session: SpeakingSessionRecord, target: SpeakingTurnRecord?,
        turns: List<SpeakingTurnRecord>, type: SpeakingAssistanceType,
        text: String, next: Int,
    ): JsonObject {
        val targetKey = target?.id?.toString() ?: "opening"
        val snapshot = session.snapshot
        return buildJsonObject {
            put("requestId", "speaking-assistance-${session.id}-$targetKey-${type.name}")
            put("idempotencyKey", "speaking-assistance:${session.id}:$targetKey:${type.name}")
            put("sessionId", session.id.toString()); put("turnIndex", next); put("assistanceType", type.name)
            put("originLanguage", snapshot.originLanguage); put("learningLanguage", snapshot.learningLanguage)
            put("topic", snapshot.topicTitle)
            put(
                "targetLevel",
                snapshot.topicId?.let(records::topic)?.recommendedLevel?.let(::JsonPrimitive) ?: JsonNull,
            )
            put("assistantText", text)
            put(
                "conversationHistory",
                JsonArray(
                    turns.flatMap { turn ->
                        buildList {
                            turn.content.transcript?.takeIf(String::isNotBlank)
                                ?.let { add(message("USER", it, turn.id)) }
                            turn.content.assistantText?.takeIf(String::isNotBlank)
                                ?.let { add(message("ASSISTANT", it, turn.id)) }
                        }
                    },
                ),
            )
            put("selectedKeywords", JsonArray(snapshot.selectedKeywords))
            put("sessionSummary", session.sessionSummary?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    private fun message(role: String, text: String, id: Long) = buildJsonObject {
        put("role", role); put("text", text); put("turnId", id.toString())
    }

    private fun response(
        context: AssistanceContext, type: SpeakingAssistanceType, text: String?, audio: String?, speed: Double,
    ) = buildJsonObject {
        put("type", type.name)
        put("targetTurnId", context.turn?.id?.let { JsonPrimitive(LearningPublicId.encode(it)) } ?: JsonNull)
        put("appliesToTurnIndex", context.next)
        put("content", text?.let(::JsonPrimitive) ?: JsonNull)
        put("audioUrl", audio?.let(::JsonPrimitive) ?: JsonNull)
        put("playbackRate", speed)
    }

    private data class AssistanceContext(
        val session: SpeakingSessionRecord, val turn: SpeakingTurnRecord?, val next: Int,
        val text: String, val audioUrl: String?, val request: JsonObject,
    )
}
