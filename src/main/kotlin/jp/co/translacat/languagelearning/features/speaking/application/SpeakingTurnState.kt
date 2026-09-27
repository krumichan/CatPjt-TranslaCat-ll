package jp.co.translacat.languagelearning.features.speaking.application

import jp.co.translacat.languagelearning.features.speaking.domain.*
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
internal data class SpeakingUploadRequest(
    val turnIndex: Int,
    val idempotencyKey: String? = null,
    val problemIndex: Int? = null,
    val attemptIndex: Int? = null,
)

internal data class SpeakingTurnClaim(
    val userId: Long,
    val session: SpeakingSessionRecord,
    val turn: SpeakingTurnRecord,
    val token: String,
    val rerecord: Boolean,
)

internal class SpeakingTurnState(private val work: SpeakingUnitOfWork) {
    suspend fun grant(userId: Long, sessionId: Long, request: SpeakingUploadRequest): SpeakingTurnRecord =
        work.write(userId) {
            // 원본의 active·순서·slot 검사를 멱등 조회보다 먼저 적용한다.
            val session = activeSession(userId, sessionId)
            val turns = records.turns(sessionId)
            val readAloud = session.snapshot.practiceMode == SpeakingPracticeMode.READ_ALOUD
            val expected = if (readAloud) (turns.maxOfOrNull { it.turnIndex } ?: 0) + 1 else session.completedTurns + 1
            if (request.idempotencyKey.isNullOrBlank() || request.turnIndex != expected || request.turnIndex > session.snapshot.maxTurns) {
                throw SpeakingFailure("INVALID_TURN_ORDER")
            }
            if (readAloud) validateSlot(sessionId, request, turns)
            else if (request.problemIndex != null || request.attemptIndex != null) throw SpeakingFailure(
                "INVALID_TURN_ORDER",
            )
            if (session.totalDurationSeconds >= session.snapshot.policy.maxSessionSeconds) throw SpeakingFailure(
                "SESSION_NOT_ACTIVE",
            )

            // 업로드 허가는 10분이며 실제 음성 결과나 기존 답변을 변경하지 않는다.
            turns.firstOrNull { it.idempotencyKey == request.idempotencyKey }?.let { return@write it }
            if (turns.any { it.turnIndex == request.turnIndex }) throw SpeakingFailure("TURN_ALREADY_EXISTS")
            records.saveTurn(
                SpeakingTurnRecord(
                    sessionId = sessionId, turnIndex = request.turnIndex,
                    idempotencyKey = request.idempotencyKey, problemIndex = request.problemIndex,
                    attemptIndex = request.attemptIndex,
                    uploadToken = UUID.randomUUID().toString(), uploadExpiresAt = nowUtc.plusMinutes(10),
                ),
            )
        }

    suspend fun rerecordGrant(userId: Long, sessionId: Long, turnId: Long): SpeakingTurnRecord = work.write(userId) {
        // 재녹음 허가는 revision·token만 바꾸고 성공 전까지 이전 발화·음성·평가 근거를 보존한다.
        val session = activeSession(userId, sessionId)
        if (session.snapshot.practiceMode != SpeakingPracticeMode.READ_ALOUD) throw SpeakingFailure(
            "INVALID_TURN_ORDER",
        )
        val turn = ownedTurn(sessionId, turnId)
        requireUnsubmitted(session, turn, "TURN_PROCESSING")
        records.saveTurn(
            turn.copy(
                recordingRevision = turn.recordingRevision + 1,
                uploadToken = UUID.randomUUID().toString(), uploadExpiresAt = nowUtc.plusMinutes(10),
            ),
        )
    }

    suspend fun exclude(userId: Long, sessionId: Long, turnId: Long): SpeakingTurnRecord = work.write(userId) {
        // 제출된 문제나 완료 세션의 증거 집합은 사후 변경하지 않는다.
        val session = records.session(userId, sessionId) ?: throw SpeakingFailure("SESSION_NOT_FOUND")
        if (!session.active) throw SpeakingFailure("SESSION_NOT_ACTIVE")
        val turn = ownedTurn(sessionId, turnId)
        requireUnsubmitted(session, turn, "SESSION_NOT_ACTIVE")
        records.saveTurn(
            turn.copy(
                excludedFromEvaluation = true,
                status = if (turn.status == SpeakingTurnStatus.READY) SpeakingTurnStatus.EXCLUDED else turn.status,
            ),
        )
    }

    suspend fun claim(
        userId: Long,
        sessionId: Long,
        turnId: Long,
        uploadToken: String?,
        rerecord: Boolean,
        manualRetry: Boolean = false,
        incomingDurationSeconds: Double? = null,
    ): SpeakingTurnClaim? = work.write(userId) {
        // 동일 turn의 모델 실행은 token과 recording revision에 귀속한다.
        val session = activeSession(userId, sessionId)
        val turn = ownedTurn(sessionId, turnId)
        requireUnsubmitted(session, turn, "TURN_PROCESSING")
        if (!rerecord && !manualRetry && turn.status != SpeakingTurnStatus.AWAITING_UPLOAD) return@write null
        if (manualRetry && turn.status in setOf(
                SpeakingTurnStatus.READY, SpeakingTurnStatus.EXCLUDED,
            )
        ) return@write null
        if (manualRetry && turn.status == SpeakingTurnStatus.PROCESSING) return@write null
        if (manualRetry && turn.status !in setOf(SpeakingTurnStatus.FAILED, SpeakingTurnStatus.PARTIAL_FAILURE)) {
            throw SpeakingFailure("TURN_PROCESSING")
        }
        if (manualRetry && turn.manualRetryCount >= session.snapshot.policy.manualRetryLimitPerStage) {
            throw SpeakingFailure("TURN_PROCESSING")
        }
        if (rerecord) {
            if (session.snapshot.practiceMode != SpeakingPracticeMode.READ_ALOUD || turn.problemIndex == null || turn.attemptIndex == null) {
                throw SpeakingFailure("INVALID_AUDIO")
            }
            if (turn.status in setOf(
                    SpeakingTurnStatus.PROCESSING, SpeakingTurnStatus.AWAITING_UPLOAD,
                )
            ) throw SpeakingFailure("TURN_PROCESSING")
        }
        if (!manualRetry && !turn.tokenValid(uploadToken, nowUtc)) throw SpeakingFailure("AUDIO_UPLOAD_EXPIRED")
        if (turn.executionToken != null && turn.executionLeaseUntil?.isAfter(nowUtc) == true) throw SpeakingFailure(
            "TURN_PROCESSING",
        )
        if (incomingDurationSeconds != null && records.audio(session.id, turn.id, "USER") == null) {
            SpeakingSessionPolicy.requireTurnAllowed(
                records.sessions(userId, session.learningDate, session.learningDate)
                    .sumOf { it.totalDurationSeconds },
                Math.round(incomingDurationSeconds), session.snapshot.policy,
            )
        }

        // 재녹음 중에도 기존 READY 결과는 유지한다. 공개 상태 변경은 성공 결과 적용 시점에 한다.
        val token = UUID.randomUUID().toString()
        val policy = session.snapshot.policy
        // 소유권 lease는 원본 STT·대화·TTS의 세 단계별 최대 시도 예산을 모두 포함한다.
        val duration = (policy.sttTimeoutSeconds + policy.ttsTimeoutSeconds + 30) * (minOf(
            2, policy.automaticRetryLimitPerStage,
        ) + 1)
        val claimed = records.saveTurn(
            turn.copy(
                executionToken = token, executionLeaseUntil = nowUtc.plusSeconds(duration.toLong()),
                status = if (rerecord) turn.status else SpeakingTurnStatus.PROCESSING,
                manualRetryCount = turn.manualRetryCount + if (manualRetry) 1 else 0,
            ),
        )
        SpeakingTurnClaim(userId, session, claimed, token, rerecord)
    }

    suspend fun saveUpload(
        claim: SpeakingTurnClaim, audio: SpeakingAudioRecord, duration: Double,
        assistance: List<SpeakingAssistanceType>,
    ): Boolean = work.write(claim.userId) {
        val turn = records.turn(claim.session.id, claim.turn.id) ?: return@write false
        if (!owns(turn, claim)) return@write false

        // 최초 업로드는 후속 단계 재시도를 위해 보존하고, 재녹음 파일은 성공 시에만 공개한다.
        check(
            !claim.rerecord && audio.sessionId == turn.sessionId && audio.turnId == turn.id &&
                audio.recordingRevision == turn.recordingRevision && audio.role == "USER",
        )
        records.saveAudio(audio)
        records.saveTurn(
            turn.copy(content = turn.content.copy(durationSeconds = duration, assistanceUsage = assistance)),
        )
        true
    }

    suspend fun publish(
        claim: SpeakingTurnClaim, content: SpeakingTurnContent, summary: String?,
        audio: List<SpeakingAudioRecord> = emptyList(),
    ): Boolean = work.write(claim.userId) {
        // 늦은 실행·재녹음 응답은 token과 revision이 모두 일치할 때만 반영한다.
        val session = records.session(claim.userId, claim.session.id) ?: return@write false
        val turn = records.turn(session.id, claim.turn.id) ?: return@write false
        if (!owns(turn, claim) || !session.active) return@write false
        requireUnsubmitted(session, turn, "TURN_PROCESSING")
        val previous =
            if (claim.rerecord && turn.status in setOf(SpeakingTurnStatus.READY, SpeakingTurnStatus.EXCLUDED))
                turn.content.durationSeconds else null
        audio.forEach {
            check(it.sessionId == session.id && it.turnId == turn.id && it.recordingRevision == turn.recordingRevision)
            records.saveAudio(it)
        }
        records.saveTurn(
            turn.copy(
                status = SpeakingTurnStatus.READY, content = content, completedAt = nowUtc,
                excludedFromEvaluation = if (claim.rerecord) false else turn.excludedFromEvaluation,
                failedStage = null, errorCode = null, errorMessage = null, executionToken = null,
                executionLeaseUntil = null,
                manualRetryCount = if (claim.rerecord) 0 else turn.manualRetryCount,
            ),
        )
        records.saveSession(session.registerTurn(content.durationSeconds, previous, summary, content.usage, nowUtc))
        records.addUsage(session.id, turn.id, content.usage, turn.manualRetryCount, nowUtc)
        true
    }

    suspend fun partial(
        claim: SpeakingTurnClaim, content: SpeakingTurnContent, stage: String, code: String,
        audio: List<SpeakingAudioRecord> = emptyList(),
    ): Boolean = work.write(claim.userId) {
        val turn = records.turn(claim.session.id, claim.turn.id) ?: return@write false
        if (!owns(turn, claim)) return@write false

        // 후속 대화/TTS 실패에서도 성공한 STT를 재사용한다. 재녹음 실패는 사용량 외의 기존 결과를 바꾸지 않는다.
        records.addUsage(claim.session.id, turn.id, content.usage, turn.manualRetryCount, nowUtc)
        if (claim.rerecord) {
            records.saveTurn(turn.copy(executionToken = null, executionLeaseUntil = null))
        } else {
            audio.forEach {
                check(
                    it.sessionId == turn.sessionId && it.turnId == turn.id && it.recordingRevision == turn.recordingRevision,
                )
                records.saveAudio(it)
            }
            records.saveTurn(
                turn.copy(
                    status = SpeakingTurnStatus.PARTIAL_FAILURE, content = content,
                    failedStage = stage, errorCode = code, errorMessage = "Speaking Turn 처리 중 일부 단계가 실패했습니다.",
                    executionToken = null, executionLeaseUntil = null,
                ),
            )
        }
        true
    }

    suspend fun fail(claim: SpeakingTurnClaim, stage: String, code: String): Boolean = work.write(claim.userId) {
        val turn = records.turn(claim.session.id, claim.turn.id) ?: return@write false
        if (!owns(turn, claim)) return@write false

        // 재녹음 실패는 결과를 덮어쓰지 않으며 외부 응답 본문이나 학습자 원문을 오류 열에 저장하지 않는다.
        records.saveTurn(
            if (claim.rerecord) turn.copy(executionToken = null, executionLeaseUntil = null)
            else turn.copy(
                status = SpeakingTurnStatus.FAILED, failedStage = stage, errorCode = code,
                errorMessage = "Speaking Turn 처리에 실패했습니다.", executionToken = null, executionLeaseUntil = null,
            ),
        )
        true
    }

    suspend fun recoverExpired(): Int {
        val due = work.read { records.expiredTurns(nowUtc, 100) }
        var count = 0
        for ((userId, sessionId, turnId) in due) if (work.write(userId) {
                val turn = records.turn(sessionId, turnId) ?: return@write false
                if (turn.executionToken == null || turn.executionLeaseUntil?.isAfter(
                        nowUtc,
                    ) != false
                ) return@write false

                // 프로세스 유실은 새 유료 재시도를 만들지 않는다. 기존 수동 재시도로 복구할 수 있게 실패만 확정한다.
                records.saveTurn(
                    if (turn.status == SpeakingTurnStatus.PROCESSING)
                        turn.copy(
                            status = SpeakingTurnStatus.FAILED, failedStage = "STT",
                            errorCode = "EXECUTION_LEASE_EXPIRED",
                            errorMessage = "Speaking 처리 연결이 종료되었습니다. 다시 시도해 주세요.", executionToken = null,
                            executionLeaseUntil = null,
                        )
                    else turn.copy(executionToken = null, executionLeaseUntil = null),
                )
                true
            }) count++
        return count
    }

    private fun SpeakingTransaction.activeSession(userId: Long, sessionId: Long): SpeakingSessionRecord {
        val stored = records.session(userId, sessionId) ?: throw SpeakingFailure("SESSION_NOT_FOUND")
        val session = stored.expireIfNeeded(nowUtc)
        if (session != stored) records.saveSession(session)
        if (!session.active) throw SpeakingFailure("SESSION_NOT_ACTIVE")
        return session
    }

    private fun SpeakingTransaction.ownedTurn(sessionId: Long, turnId: Long) =
        records.turn(sessionId, turnId) ?: throw SpeakingFailure("TURN_NOT_FOUND")

    private fun SpeakingTransaction.requireUnsubmitted(
        session: SpeakingSessionRecord, turn: SpeakingTurnRecord, code: String,
    ) {
        if (session.snapshot.practiceMode == SpeakingPracticeMode.READ_ALOUD && turn.problemIndex != null &&
            records.result(session.id, turn.problemIndex) != null
        ) throw SpeakingFailure(code)
    }

    private fun SpeakingTransaction.validateSlot(
        sessionId: Long, request: SpeakingUploadRequest, turns: List<SpeakingTurnRecord>,
    ) {
        val problem = request.problemIndex ?: throw SpeakingFailure("INVALID_TURN_ORDER")
        val attempt = request.attemptIndex ?: throw SpeakingFailure("INVALID_TURN_ORDER")
        if (problem !in 1..5 || attempt !in 1..3) throw SpeakingFailure("INVALID_TURN_ORDER")
        val submitted = records.results(sessionId).filter { it.problemIndex > 0 }.map { it.problemIndex }.toSet()
        if (problem != (submitted.size + 1).coerceAtMost(5) || problem in submitted) throw SpeakingFailure(
            "INVALID_TURN_ORDER",
        )
        if (turns.any { it.problemIndex == problem && it.attemptIndex == attempt }) throw SpeakingFailure(
            "TURN_ALREADY_EXISTS",
        )
        if (attempt != turns.count { it.problemIndex == problem } + 1) throw SpeakingFailure("INVALID_TURN_ORDER")
    }

    private fun owns(turn: SpeakingTurnRecord, claim: SpeakingTurnClaim) =
        turn.executionToken == claim.token && turn.recordingRevision == claim.turn.recordingRevision
}
