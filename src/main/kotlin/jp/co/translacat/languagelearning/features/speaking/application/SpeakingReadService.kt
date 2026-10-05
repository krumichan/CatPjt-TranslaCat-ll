package jp.co.translacat.languagelearning.features.speaking.application

import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.domain.policy.UserSettingsPolicy
import jp.co.translacat.languagelearning.features.speaking.domain.*
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import kotlinx.serialization.json.*

internal class SpeakingReadService(
    private val work: SpeakingUnitOfWork, private val settings: SettingsServiceOperations,
) {
    suspend fun session(userId: Long, sessionId: Long): JsonObject = work.read {
        val session = owned(userId, sessionId)
        val turns = records.turns(sessionId)
        val coaching = if (session.snapshot.resultKind == SpeakingResultKind.SESSION_COACHING)
            coachingView(session, turns) else JsonNull
        sessionView(session, coaching, turns)
    }

    suspend fun detail(userId: Long, sessionId: Long): JsonObject {
        val current = settings.userSnapshot(userId)
        val admin = settings.adminPolicy()
        return work.write(userId) {
            // 원본 상세 조회와 같이 TTL 만료를 저장한 뒤 세션/턴/결과를 동일 시점으로 읽는다.
            val stored = owned(userId, sessionId)
            val value = stored.expireIfNeeded(nowUtc)
            if (stored != value) records.saveSession(value)
            val turns = records.turns(sessionId)
            val coaching = coachingView(value, turns)
            val today = records.sessions(userId, current.learningDate, current.learningDate)
            buildJsonObject {
                put("session", sessionView(value, coaching, turns))
                put(
                    "dailyUsage",
                    buildJsonObject {
                        put("sessionCount", today.size)
                        put("usedMinutes", Math.round(today.sumOf { it.totalDurationSeconds } / 60.0 * 100) / 100.0)
                        put("dailySessionLimit", admin.dailySpeakingSessionLimit)
                        put("dailySpeakingHardLimitMinutes", admin.dailySpeakingHardLimitMinutes)
                        put("dailyGoalMinutes", current.result.settings.dailySpeakingGoalMinutes)
                    },
                )
                put("turns", JsonArray(turns.map { turnView(it) }))
                put("readAloudProblemEvaluations", problemViews(value))
                put(
                    "evaluationEligibility",
                    eligibilityView(
                        SpeakingPolicy.completionEligibility(
                            value.snapshot.practiceMode, turns.map { it.completionEvidence() },
                        ),
                    ),
                )
                put("coachingResult", coaching)
                put(
                    "resumable",
                    SpeakingSessionPolicy.resumable(value.active, value.lastActivityAt, nowUtc, value.snapshot.policy),
                )
            }
        }
    }

    suspend fun active(userId: Long): JsonObject? {
        val session = work.write(userId) {
            val stored = records.active(userId) ?: return@write null
            val value = stored.expireIfNeeded(nowUtc)
            if (stored != value) records.saveSession(value)
            value.takeIf { it.active }
        } ?: return null
        return detail(userId, session.id)
    }

    suspend fun today(userId: Long): JsonArray {
        val current = settings.userSnapshot(userId)
        UserSettingsPolicy.requireConfigured(current.result.settings)
        return work.read {
            val sessions = records.sessions(userId, current.learningDate, current.learningDate)
            JsonArray(
                SpeakingPracticeMode.entries.map { mode ->
                    val value = sessions.firstOrNull { it.snapshot.practiceMode == mode }
                    buildJsonObject {
                        put("practiceMode", mode.name)
                        put("sessionId", value?.id?.let { JsonPrimitive(LearningPublicId.encode(it)) } ?: JsonNull)
                        put("sessionStatus", value?.status?.name?.let(::JsonPrimitive) ?: JsonNull)
                        put("evaluationStatus", value?.evaluationStatus?.name?.let(::JsonPrimitive) ?: JsonNull)
                        put("resultKind", value?.snapshot?.resultKind?.name?.let(::JsonPrimitive) ?: JsonNull)
                        put(
                            "resultPolicyVersion",
                            value?.snapshot?.resultPolicyVersion?.let(::JsonPrimitive) ?: JsonNull,
                        )
                        put("resultStatus", value?.let { resultStatus(it) } ?: "NOT_REQUESTED")
                        put("completedTurns", value?.completedTurns ?: 0)
                        put("maxTurns", value?.snapshot?.maxTurns ?: 0)
                        put(
                            "completed",
                            value != null && if (value.snapshot.resultKind == SpeakingResultKind.SESSION_COACHING)
                                value.completedAt != null else value.evaluationStatus in setOf(
                                SpeakingEvaluationStatus.EVALUATED, SpeakingEvaluationStatus.INSUFFICIENT_EVIDENCE,
                            ),
                        )
                    }
                },
            )
        }
    }

    suspend fun turn(userId: Long, sessionId: Long, turnId: Long): JsonObject = work.read {
        owned(userId, sessionId)
        turnView(records.turn(sessionId, turnId) ?: throw SpeakingFailure("TURN_NOT_FOUND"))
    }

    suspend fun problems(userId: Long, sessionId: Long): JsonArray =
        work.read { problemViews(owned(userId, sessionId)) }

    suspend fun historyPayload(userId: Long, sessionId: Long): JsonObject = work.read {
        // 과거 이력 조회는 원본처럼 상태를 변경하지 않고 현재 보관 중인 세션·턴·코칭 snapshot을 반환한다.
        val session = owned(userId, sessionId)
        val turns = records.turns(sessionId)
        val coaching = coachingView(session, turns)
        buildJsonObject {
            put("session", sessionView(session, coaching, turns))
            put("turns", JsonArray(turns.map { turnView(it) }))
            put("coachingResult", coaching)
        }
    }

    private fun SpeakingTransaction.owned(userId: Long, sessionId: Long) =
        records.session(userId, sessionId)?.takeIf { it.openingReady } ?: throw SpeakingFailure("SESSION_NOT_FOUND")

    private fun SpeakingTransaction.resultStatus(value: SpeakingSessionRecord): String =
        if (value.snapshot.resultKind == SpeakingResultKind.SESSION_COACHING)
            records.job(value.id, 0)?.status?.name ?: "NOT_REQUESTED" else value.evaluationStatus.name

    private fun SpeakingTransaction.sessionView(
        value: SpeakingSessionRecord, coaching: JsonElement, turns: List<SpeakingTurnRecord>,
    ): JsonObject {
        val snapshot = value.snapshot
        val scored = snapshot.resultKind == SpeakingResultKind.SCORED_EVALUATION &&
            snapshot.resultPolicyVersion == "speaking-evaluation-policy-v2"
        val coachingPolicy = snapshot.resultKind == SpeakingResultKind.SESSION_COACHING &&
            snapshot.resultPolicyVersion == "free-session-coaching-v1"
        val sourceAvailability = when {
            !scored && !coachingPolicy -> "UNAVAILABLE"
            scored -> scoredSummaryAvailability(value, turns)
            else -> (coaching as? JsonObject)?.get("evidenceAvailability")
                ?.jsonPrimitive?.contentOrNull ?: "UNVERIFIED"
        }
        // 대화 요약과 코칭 근거는 서로 다른 계약이다. 알려진 점수형 세션은 코칭 부재만으로 숨기지 않는다.
        val restricted = sourceAvailability in setOf("LIMITED", "UNAVAILABLE") ||
            (sourceAvailability == "UNVERIFIED" && !value.active && !scored)
        return buildJsonObject {
            put("id", LearningPublicId.encode(value.id))
            put("learningDate", value.learningDate.toString())
            put("topicId", snapshot.topicId?.let { JsonPrimitive(LearningPublicId.encode(it)) } ?: JsonNull)
            put("topicTitle", snapshot.topicTitle)
            put("topicCategory", snapshot.topicCategory)
            put("topicVersion", snapshot.topicVersion?.let(::JsonPrimitive) ?: JsonNull)
            put("customTopic", snapshot.customTopic?.let(::JsonPrimitive) ?: JsonNull)
            put("goal", snapshot.goal?.let(::JsonPrimitive) ?: JsonNull)
            put("persona", snapshot.persona?.let(::JsonPrimitive) ?: JsonNull)
            put("originLanguage", snapshot.originLanguage)
            put("learningLanguage", snapshot.learningLanguage)
            put("status", value.status.name)
            put("evaluationStatus", value.evaluationStatus.name)
            put("resultKind", snapshot.resultKind.name)
            put("resultPolicyVersion", snapshot.resultPolicyVersion)
            put("resultStatus", resultStatus(value))
            put("practiceMode", snapshot.practiceMode.name)
            put("conversationStartMode", snapshot.conversationStartMode.name)
            put("resolvedStartMode", snapshot.resolvedStartMode.name)
            put("correctionMode", snapshot.correctionMode.name)
            put("targetMinutes", snapshot.targetMinutes)
            put("maxTurns", snapshot.maxTurns)
            put("completedTurns", value.completedTurns)
            put("totalDurationSeconds", value.totalDurationSeconds)
            put("voiceId", snapshot.voiceId)
            put("playbackSpeed", snapshot.playbackSpeed)
            put("openingAssistantText", value.opening["assistantText"] ?: JsonNull)
            put("openingPromptGuide", promptGuide(value.opening["conversation"] as? JsonObject))
            put(
                "openingAssistantAudioUrl",
                if (!audioAvailable(value.id, null, "OPENING")) JsonNull
                else JsonPrimitive("${sessionPath(value.id)}/audio/opening"),
            )
            // 현재 원본과 달라진 코칭의 요약에는 삭제된 발화가 남을 수 있어 세 조회 경로에서 함께 숨긴다.
            put("sessionSummary", if (restricted) JsonNull else value.sessionSummary?.let(::JsonPrimitive) ?: JsonNull)
            put("summaryEvidenceAvailability", sourceAvailability)
            put("summaryEvidenceLimitations", when {
                !scored && !coachingPolicy -> JsonArray(listOf(JsonPrimitive("UNKNOWN_RESULT_POLICY")))
                sourceAvailability == "UNVERIFIED" -> JsonArray(listOf(JsonPrimitive("SUMMARY_SOURCE_PROVENANCE_UNAVAILABLE")))
                scored && restricted -> JsonArray(listOf(JsonPrimitive("SOURCE_EVIDENCE_CHANGED_OR_UNAVAILABLE")))
                restricted -> (coaching as JsonObject).getValue("evidenceLimitations")
                else -> JsonArray(emptyList())
            })
            put("startedAt", value.startedAt.toString())
            put("completedAt", value.completedAt?.toString()?.let(::JsonPrimitive) ?: JsonNull)
            put("lastActivityAt", value.lastActivityAt.toString())
        }
    }

    private fun SpeakingTransaction.scoredSummaryAvailability(
        session: SpeakingSessionRecord, turns: List<SpeakingTurnRecord>,
    ): String {
        val job = records.job(session.id, 0)
        if (job == null) {
            // 과거 점수형 요약의 표시 계약을 보존하되, 남은 원본조차 없거나 제외된 요약은 노출하지 않는다.
            // 저장 요청이 없으면 현재 transcript의 완전한 provenance를 새로 인증하지 않는다.
            if (session.active) return "UNVERIFIED"
            val retained = turns.isNotEmpty() && session.completedTurns > 0 &&
                turns.size == session.completedTurns && turns.all {
                    it.sessionId == session.id && !it.excludedFromEvaluation &&
                        !it.content.transcript.isNullOrBlank() && it.status == SpeakingTurnStatus.READY
                }
            return if (retained) "UNVERIFIED" else "UNAVAILABLE"
        }

        // 저장된 전체 세션 요청을 현재 원본과 대조한다. 점수·평가 성공 여부를 다시 판정하지 않는다.
        val snapshot = session.snapshot
        val request = job.request
        val sourceMatches = runCatching {
            job.sessionId == session.id && job.problemIndex == 0 &&
                job.resultKind == SpeakingResultKind.SCORED_EVALUATION &&
                job.resultPolicyVersion == snapshot.resultPolicyVersion &&
                request["sessionId"] == JsonPrimitive(session.id.toString()) &&
                request["evaluationScope"] == JsonPrimitive("SESSION") &&
                request["evaluationPolicyVersion"] == JsonPrimitive(snapshot.resultPolicyVersion) &&
                request["practiceMode"] == JsonPrimitive(snapshot.practiceMode.name) &&
                request["originLanguage"] == JsonPrimitive(snapshot.originLanguage) &&
                request["learningLanguage"] == JsonPrimitive(snapshot.learningLanguage) &&
                request["sessionSummary"] == (session.sessionSummary?.let(::JsonPrimitive) ?: JsonNull) &&
                SpeakingCoachingReadPolicy.sourceMatches(session, request, turns)
        }.getOrDefault(false)
        return if (sourceMatches) "AVAILABLE" else "UNAVAILABLE"
    }

    private fun SpeakingTransaction.turnView(value: SpeakingTurnRecord): JsonObject = buildJsonObject {
        put("id", LearningPublicId.encode(value.id))
        put("turnIndex", value.turnIndex)
        put("problemIndex", value.problemIndex?.let(::JsonPrimitive) ?: JsonNull)
        put("attemptIndex", value.attemptIndex?.let(::JsonPrimitive) ?: JsonNull)
        put("recordingRevision", value.recordingRevision)
        put("status", value.status.name)
        put("durationSeconds", value.content.durationSeconds)
        put("transcript", value.content.transcript?.let(::JsonPrimitive) ?: JsonNull)
        put("sttConfidence", value.content.sttConfidence?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "userAudioUrl",
            if (!audioAvailable(value.sessionId, value.id, "USER", value.recordingRevision)) JsonNull
            else JsonPrimitive("${sessionPath(value.sessionId)}/turns/${LearningPublicId.encode(value.id)}/audio/user"),
        )
        put("assistantText", value.content.assistantText?.let(::JsonPrimitive) ?: JsonNull)
        put("promptGuide", promptGuide(value.content.conversation))
        put(
            "assistantAudioUrl",
            if (!audioAvailable(value.sessionId, value.id, "ASSISTANT", value.recordingRevision)) JsonNull
            else JsonPrimitive("${sessionPath(value.sessionId)}/turns/${LearningPublicId.encode(value.id)}/audio"),
        )
        put("assistanceUsage", JsonArray(value.content.assistanceUsage.map { JsonPrimitive(it.name) }))
        put("excludedFromEvaluation", value.excludedFromEvaluation)
        put("failedStage", value.failedStage?.let(::JsonPrimitive) ?: JsonNull)
        put("errorCode", value.errorCode?.let(::JsonPrimitive) ?: JsonNull)
        put("errorMessage", value.errorMessage?.let(::JsonPrimitive) ?: JsonNull)
        put("manualRetryCount", value.manualRetryCount)
        put("completedAt", value.completedAt?.toString()?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun SpeakingTransaction.problemViews(session: SpeakingSessionRecord): JsonArray =
        if (session.snapshot.practiceMode != SpeakingPracticeMode.READ_ALOUD) JsonArray(emptyList())
        else JsonArray(
            records.results(session.id).filter { it.problemIndex > 0 }.map { result ->
                buildJsonObject {
                    put("problemIndex", result.problemIndex)
                    put("status", result.status)
                    listOf(
                        "attemptCount", "overallScore", "evaluationConfidence", "errorMessage", "submittedAt",
                        "evaluatedAt",
                        "manualRetryCount", "manualRetryLimit", "evaluatedAxes", "evaluationCoverage",
                        "evidencePolicyVersion", "evidenceSource",
                    )
                        .forEach { key -> put(key, result.response[key] ?: JsonNull) }
                }
            },
        )

    private fun SpeakingTransaction.coachingView(
        session: SpeakingSessionRecord, turns: List<SpeakingTurnRecord>,
    ): JsonElement {
        if (session.snapshot.resultKind != SpeakingResultKind.SESSION_COACHING) return JsonNull
        val result = records.result(session.id, 0) ?: return JsonNull
        val response = SpeakingCoachingReadPolicy.project(session, result, records.job(session.id, 0), turns)
        return buildJsonObject {
            put("id", LearningPublicId.encode(result.id))
            listOf(
                "resultKind", "resultPolicyVersion", "schemaVersion", "sourceSnapshotHash", "contentStatus",
                "limitationReasons", "items", "promptVersion", "evidenceAvailability", "evidenceLimitations",
            )
                .forEach { key -> put(key, response[key] ?: JsonNull) }
            put("createdAt", result.updatedAt.toString())
        }
    }

    private fun SpeakingTransaction.audioAvailable(
        sessionId: Long, turnId: Long?, role: String, revision: Int? = null,
    ): Boolean {
        // 보존 작업이 아직 삭제 표시를 쓰지 않았어도 만료된 음성을 재생 가능한 링크로 내보내지 않는다.
        val audio = records.audio(sessionId, turnId, role) ?: return false
        return audio.deletedAt == null && audio.physicalDeletedAt == null && audio.retentionUntil.isAfter(nowUtc) &&
            (revision == null || audio.recordingRevision == revision)
    }

    private fun promptGuide(value: JsonObject?) = buildJsonObject {
        put("scriptText", value?.get("scriptText") ?: JsonNull)
        listOf("providedFacts", "requiredIntents", "responseConstraints").forEach { key ->
            put(key, value?.get(key)?.takeUnless { it == JsonNull } ?: JsonArray(emptyList()))
        }
    }

    private fun eligibilityView(value: SpeakingEligibility) = buildJsonObject {
        put("validUserTurns", value.validUserTurns)
        put("validUserSpeechSeconds", value.validUserSpeechSeconds)
        put("validSttTurnRatio", value.validSttTurnRatio)
        put("requiredUserTurns", value.requiredUserTurns)
        put("requiredUserSpeechSeconds", value.requiredSpeechSeconds)
        put("requiredSttTurnRatio", value.requiredSttTurnRatio)
        put("requiredEvaluationConfidence", value.requiredEvaluationConfidence)
        put("eligible", value.eligibleBeforeAi)
        put("missingRequirements", JsonArray(value.missingRequirements.map(::JsonPrimitive)))
    }

    private fun sessionPath(id: Long) = "/api/v1/language-learning/speaking/sessions/${LearningPublicId.encode(id)}"
}
