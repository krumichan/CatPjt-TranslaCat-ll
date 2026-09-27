package jp.co.translacat.languagelearning.features.speaking.application

import jp.co.translacat.languagelearning.features.growth.application.GrowthProjector
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthActivity
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthChange
import jp.co.translacat.languagelearning.features.speaking.domain.*
import kotlinx.serialization.json.*

internal class SpeakingSessionState(private val work: SpeakingUnitOfWork) {
    suspend fun complete(userId: Long, sessionId: Long, skipEvaluation: Boolean = false): SpeakingSessionRecord =
        work.write(userId) {
            val session = records.session(userId, sessionId) ?: throw SpeakingFailure("SESSION_NOT_FOUND")
            completeSpeaking(session, skipEvaluation)
        }

    suspend fun submitProblem(userId: Long, sessionId: Long, problemIndex: Int): SpeakingResultRecord =
        work.write(userId) {
            // 제출 응답 유실은 완료 후에도 재조회할 수 있지만 새 문제는 활성 세션에서만 제출한다.
            val stored = records.session(userId, sessionId) ?: throw SpeakingFailure("SESSION_NOT_FOUND")
            if (stored.snapshot.practiceMode != SpeakingPracticeMode.READ_ALOUD || problemIndex !in 1..5) throw SpeakingFailure(
                "EVALUATION_FAILED",
            )
            records.result(sessionId, problemIndex)?.let { return@write it }
            val session = stored.expireIfNeeded(nowUtc)
            if (session != stored) records.saveSession(session)
            if (!session.active) throw SpeakingFailure("SESSION_NOT_ACTIVE")
            val submitted = records.results(sessionId).filter { it.problemIndex > 0 }
            if (submitted.count { it.problemIndex < problemIndex } != problemIndex - 1) throw SpeakingFailure(
                "EVALUATION_FAILED",
            )
            val attempts = records.turns(sessionId).filter { it.problemIndex == problemIndex }
            val included = attempts.filterNot { it.excludedFromEvaluation }
            if (included.size !in 2..3 || included.count { !it.content.transcript.isNullOrBlank() }
                    .toDouble() / included.size < .80) {
                throw SpeakingFailure("EVALUATION_FAILED")
            }
            if (problemIndex < 5 && attempts.none { !it.content.assistantText.isNullOrBlank() }) throw SpeakingFailure(
                "EVALUATION_FAILED",
            )

            // 문제 결과의 제출 상태와 immutable 요청을 함께 기록해 이후 녹음 교체를 막는다.
            val enabled = session.snapshot.policy.speakingEvaluationEnabled
            val response = buildJsonObject {
                put("problemIndex", problemIndex)
                put("attemptCount", included.size)
                put("submittedAt", nowUtc.toString())
                put("evaluatedAt", if (enabled) JsonNull else JsonPrimitive(nowUtc.toString()))
                put("manualRetryCount", 0)
                put("manualRetryLimit", session.snapshot.policy.manualRetryLimitPerStage.coerceAtLeast(0))
            }
            records.saveResult(
                SpeakingResultRecord(
                    sessionId, problemIndex, session.snapshot.resultKind,
                    if (enabled) "PENDING" else "NOT_REQUESTED", response, nowUtc,
                ),
            )
            if (enabled) enqueueSpeaking(session, problemIndex, speakingEvaluationRequest(session, problemIndex))
            if (problemIndex == 5 && submitted.map { it.problemIndex }.toSet().size == 4) completeSpeaking(
                session, false,
            )
            checkNotNull(records.result(sessionId, problemIndex))
        }

    suspend fun expireDue(): Int {
        val due = work.read { records.expirable(nowUtc, 100) }
        var count = 0
        for ((userId, sessionId) in due) {
            if (work.write(userId) {
                    val stored = records.session(userId, sessionId) ?: return@write false
                    val expired = stored.expireIfNeeded(nowUtc)
                    if (expired == stored) return@write false
                    records.saveSession(expired)
                    true
                }) count++
        }
        return count
    }
}

private fun SpeakingTransaction.completeSpeaking(session: SpeakingSessionRecord, skip: Boolean): SpeakingSessionRecord {
    if (session.completedAt != null && session.status != SpeakingSessionStatus.EXPIRED) return session
    if (!session.active) throw SpeakingFailure("SESSION_NOT_ACTIVE")
    val eligibility = SpeakingPolicy.completionEligibility(
        session.snapshot.practiceMode,
        records.turns(session.id).map { it.completionEvidence() },
    )
    val coaching = session.snapshot.resultKind == SpeakingResultKind.SESSION_COACHING
    val enabled = session.snapshot.policy.speakingEvaluationEnabled
    if (skip && enabled && (coaching || eligibility.eligibleBeforeAi)) throw SpeakingFailure(
        "SPEAKING_EVALUATION_SKIP_NOT_ALLOWED",
    )

    // 원본처럼 코칭은 공식 평가 상태로 표시하지 않고, 세션 완료·Activity·평가 intent만 원자적으로 저장한다.
    val requested = enabled && !skip
    val complete = records.saveSession(
        session.copy(
            status = SpeakingSessionStatus.COMPLETED,
            evaluationStatus = if (requested && !coaching) SpeakingEvaluationStatus.PENDING else SpeakingEvaluationStatus.NOT_REQUESTED,
            completedAt = nowUtc, lastActivityAt = nowUtc,
        ),
    )
    recordSpeakingActivity(complete, if (requested && !coaching) "EVALUATING" else "COMPLETED", skip)
    if (requested) enqueueSpeaking(complete, 0, speakingEvaluationRequest(complete, 0))
    return complete
}

internal fun SpeakingTransaction.recordSpeakingActivity(
    session: SpeakingSessionRecord, status: String, skip: Boolean = false,
) {
    val snapshot = session.snapshot
    val metadata = buildJsonObject {
        put("topicCategory", snapshot.topicCategory)
        put("conversationStartMode", snapshot.conversationStartMode.name)
        put("resolvedStartMode", snapshot.resolvedStartMode.name)
        put("correctionMode", snapshot.correctionMode.name)
        put("selectedKeywords", JsonArray(snapshot.selectedKeywords).toString())
        put("evaluationSkipped", skip)
        put("resultKind", snapshot.resultKind.name)
        put("resultPolicyVersion", snapshot.resultPolicyVersion)
    }
    val old = growth.activity(session.userId, "SPEAKING", "ll-speaking-${session.id}")
    GrowthProjector(growth).apply(
        session.userId,
        GrowthChange.ActivityRecorded(
            GrowthActivity(
                id = old?.id ?: 0, userId = session.userId, source = "SPEAKING",
                referenceId = "ll-speaking-${session.id}",
                learningDate = session.learningDate, title = snapshot.topicTitle,
                durationSeconds = session.totalDurationSeconds.coerceAtLeast(0),
                status = status, startedAt = session.startedAt, completedAt = session.completedAt,
                metadataJson = metadata.toString(),
                createdAt = old?.createdAt ?: nowUtc, updatedAt = nowUtc,
            ),
            null,
        ),
        nowUtc,
    )
}
