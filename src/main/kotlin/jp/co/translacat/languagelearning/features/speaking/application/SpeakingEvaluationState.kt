package jp.co.translacat.languagelearning.features.speaking.application

import jp.co.translacat.languagelearning.features.speaking.domain.*
import kotlinx.serialization.json.*
import java.util.*

internal data class SpeakingEvaluationClaim(
    val userId: Long, val session: SpeakingSessionRecord, val job: SpeakingJobRecord,
)

internal class SpeakingEvaluationState(private val work: SpeakingUnitOfWork) {
    suspend fun claim(userId: Long, sessionId: Long, problemIndex: Int): SpeakingEvaluationClaim? = work.write(userId) {
        val session = records.session(userId, sessionId) ?: return@write null
        val job = records.job(sessionId, problemIndex) ?: return@write null
        if (!job.due(nowUtc)) return@write null

        // 원본 900초 lease와 최대 2회 회수를 보존하며 이전 worker의 token을 무효화한다.
        if (job.status == SpeakingJobStatus.RUNNING && job.recoveryCount >= 2) {
            records.saveJob(
                job.copy(
                    status = SpeakingJobStatus.FAILED, claimToken = null, lastError = "EVALUATION_RECOVERY_EXHAUSTED",
                ),
            )
            changeStatus(session, job, "FAILED")
            return@write null
        }
        val claimed = records.saveJob(
            job.copy(
                status = SpeakingJobStatus.RUNNING,
                claimToken = UUID.randomUUID().toString(), availableAt = nowUtc.plusSeconds(900),
                recoveryCount = job.recoveryCount + if (job.status == SpeakingJobStatus.RUNNING) 1 else 0,
                lastError = null,
            ),
        )
        changeStatus(session, claimed, "EVALUATING")
        SpeakingEvaluationClaim(userId, session, claimed)
    }

    suspend fun complete(
        claim: SpeakingEvaluationClaim,
        response: JsonObject,
        validate: (SpeakingJobRecord, JsonObject) -> Unit,
        applyResult: SpeakingTransaction.(SpeakingSessionRecord, SpeakingJobRecord, JsonObject) -> Unit,
    ): Boolean = work.write(claim.userId) {
        // 응답 검증과 원본 결과·Growth·job 완료는 같은 트랜잭션으로 묶는다.
        val session = records.session(claim.userId, claim.session.id) ?: return@write false
        val current = records.job(session.id, claim.job.problemIndex) ?: return@write false
        if (!current.owns(claim.job.claimToken)) return@write false
        validate(current, response)
        applyResult(session, current, response)
        records.saveJob(current.copy(status = SpeakingJobStatus.SUCCEEDED, claimToken = null, lastError = null))
        true
    }

    suspend fun fail(claim: SpeakingEvaluationClaim): Boolean = work.write(claim.userId) {
        // 결과 적용이 rollback된 뒤에만 호출하며 실패 코드 외의 모델 원문은 저장하지 않는다.
        val session = records.session(claim.userId, claim.session.id) ?: return@write false
        val current = records.job(session.id, claim.job.problemIndex) ?: return@write false
        if (!current.owns(claim.job.claimToken)) return@write false
        val code =
            if (current.resultKind == SpeakingResultKind.SESSION_COACHING) "SPEAKING_COACHING_FAILED" else "SPEAKING_EVALUATION_FAILED"
        records.saveJob(current.copy(status = SpeakingJobStatus.FAILED, claimToken = null, lastError = code))
        changeStatus(session, current, "FAILED")
        true
    }

    suspend fun release(claim: SpeakingEvaluationClaim): Boolean = work.write(claim.userId) {
        val session = records.session(claim.userId, claim.session.id) ?: return@write false
        val current = records.job(session.id, claim.job.problemIndex) ?: return@write false
        if (!current.owns(claim.job.claimToken)) return@write false

        // 정상 취소는 기존 5초 뒤 실행으로 돌리고 회수 횟수를 추가하지 않는다.
        records.saveJob(
            current.copy(status = SpeakingJobStatus.PENDING, claimToken = null, availableAt = nowUtc.plusSeconds(5)),
        )
        changeStatus(session, current, "PENDING")
        true
    }

    suspend fun retry(userId: Long, sessionId: Long, problemIndex: Int): SpeakingJobRecord = work.write(userId) {
        val session = records.session(userId, sessionId) ?: throw SpeakingFailure("SESSION_NOT_FOUND")
        if (!session.snapshot.policy.speakingEvaluationEnabled) throw SpeakingFailure("SPEAKING_DISABLED")
        val job = records.job(sessionId, problemIndex) ?: throw SpeakingFailure("EVALUATION_FAILED")
        if (job.status in setOf(SpeakingJobStatus.PENDING, SpeakingJobStatus.RUNNING)) return@write job
        if (job.status != SpeakingJobStatus.FAILED || job.manualRetryCount >= session.snapshot.policy.manualRetryLimitPerStage) {
            throw SpeakingFailure("EVALUATION_FAILED")
        }

        // 제출된 증거는 다시 읽지 않는다. 원본 snapshot에서 수동 재시도 번호만 갱신한다.
        val count = job.manualRetryCount + 1
        val request = speakingRetryRequest(job, count)
        val retried = records.saveJob(
            job.copy(
                status = SpeakingJobStatus.PENDING, claimToken = null,
                request = request, availableAt = nowUtc, manualRetryCount = count, recoveryCount = 0, lastError = null,
            ),
        )
        changeStatus(session, retried, "PENDING")
        retried
    }

    private fun SpeakingTransaction.changeStatus(
        session: SpeakingSessionRecord, job: SpeakingJobRecord, status: String,
    ) {
        if (job.problemIndex == 0) {
            // 자유 대화 코칭은 공식 평가 상태나 점수 상태로 바꾸지 않는다.
            if (job.resultKind == SpeakingResultKind.SESSION_COACHING) return
            val sessionStatus = when (status) {
                "PENDING" -> SpeakingSessionStatus.COMPLETED
                "EVALUATING" -> SpeakingSessionStatus.EVALUATING
                else -> SpeakingSessionStatus.EVALUATION_FAILED
            }
            records.saveSession(
                session.copy(status = sessionStatus, evaluationStatus = SpeakingEvaluationStatus.valueOf(status)),
            )
            if (status != "PENDING") recordSpeakingActivity(
                session,
                if (status == "FAILED") "EVALUATION_FAILED" else "EVALUATING",
            )
        } else {
            val result = checkNotNull(records.result(session.id, job.problemIndex))
            val details = JsonObject(
                result.response + mapOf(
                    "manualRetryCount" to JsonPrimitive(job.manualRetryCount),
                    "errorMessage" to if (status == "FAILED") JsonPrimitive(
                        "Speaking 평가 처리에 실패했습니다. 다시 시도해 주세요.",
                    ) else JsonNull,
                    "evaluatedAt" to if (status == "FAILED") JsonPrimitive(nowUtc.toString()) else JsonNull,
                ),
            )
            records.saveResult(result.copy(status = status, response = details, updatedAt = nowUtc))
        }
    }
}

internal fun speakingRetryRequest(job: SpeakingJobRecord, count: Int): JsonObject {
    require(count > 0)
    val idempotency = job.request.getValue("idempotencyKey").jsonPrimitive.content
    // 코칭의 긴 증거 hash 키를 trace ID로 복제하면 원본 requestId 100자 계약을 넘는다. 실행 키와 증거는 그대로 보존한다.
    val requestId = if (job.resultKind == SpeakingResultKind.SESSION_COACHING)
        "speaking-coaching-${job.sessionId}-retry-$count" else "$idempotency:manual:$count"
    return JsonObject(
        job.request + mapOf("manualRetryAttempt" to JsonPrimitive(count), "requestId" to JsonPrimitive(requestId)),
    )
}

internal fun SpeakingTransaction.enqueueSpeaking(
    session: SpeakingSessionRecord,
    problemIndex: Int,
    request: JsonObject,
): SpeakingJobRecord {
    if (!session.snapshot.policy.speakingEvaluationEnabled) throw SpeakingFailure("SPEAKING_DISABLED")
    records.job(session.id, problemIndex)?.let { return it }
    return records.saveJob(
        SpeakingJobRecord(
            sessionId = session.id, problemIndex = problemIndex,
            resultKind = session.snapshot.resultKind, resultPolicyVersion = session.snapshot.resultPolicyVersion,
            sourceSnapshotHash = request["sourceSnapshotHash"]?.jsonPrimitive?.contentOrNull,
            request = request, availableAt = nowUtc,
        ),
    )
}
