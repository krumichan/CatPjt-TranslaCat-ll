package jp.co.translacat.languagelearning.features.speaking

import jp.co.translacat.languagelearning.features.speaking.application.*
import jp.co.translacat.languagelearning.features.speaking.domain.*
import jp.co.translacat.languagelearning.features.speaking.infrastructure.ExposedSpeakingUnitOfWork
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import jp.co.translacat.languagelearning.support.SettingsFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.*
import kotlin.test.*

class SpeakingStateIntegrationTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-26T04:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `삭제 소유권은 신고 보관 연장과 직렬화되고 새 pool 회수 뒤 늦은 완료를 거부한다`() = LocalScratchMysql.use { db ->
        var old: SpeakingAudioDeleteClaim? = null
        val store = object : SpeakingAudioStore {
            override suspend fun put(key: String, bytes: ByteArray, contentType: String) = Unit
            override suspend fun load(key: String) = byteArrayOf(1)
            override suspend fun delete(key: String) = Unit
        }
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 신고 전의 만료 음성과 별도의 삭제 대상 opening을 저장한다.
                val work = work(factory)
                val session = seed(work, 710)
                val turn = SpeakingTurnState(work).grant(710, session.id, SpeakingUploadRequest(1, "audio", 1, 1))
                val (userAudio, openingAudio) = work.write(710) {
                    records.saveAudio(
                        speakingAudioRecord(session, turn.id, 0, "USER", byteArrayOf(1), "audio/wav", null)
                            .copy(retentionUntil = nowUtc.minusSeconds(1)),
                    ) to
                        records.saveAudio(
                            speakingAudioRecord(session, null, 0, "OPENING", byteArrayOf(2), "audio/wav", null)
                                .copy(retentionUntil = nowUtc.minusSeconds(1)),
                        )
                }
                val retention = SpeakingAudioRetention(work, store) { fail(it) }

                // 실행: 동의한 신고가 먼저 잠금을 취득하면 물리 삭제 대상으로 잡지 않는다.
                val report = SpeakingSttReportService(work).create(
                    710, session.id, turn.id,
                    SpeakingSttReportRequest(SpeakingSttReportType.WRONG_TEXT, audioAnalysisConsent = true),
                )
                assertNotNull(report.audioRetentionUntil)
                assertNull(retention.claim(710, userAudio.id))
                assertNull(retention.claim(711, openingAudio.id))
                old = assertNotNull(retention.claim(710, openingAudio.id))

                // 검증: 삭제 tombstone은 조회를 막으며 동일 객체에 두 소유자를 허용하지 않는다.
                assertNull(retention.claim(710, openingAudio.id))
                assertNull(work.read { records.audio(session.id, null, "OPENING") })
            }
        }
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 실행: 실제 대기 대신 테스트 clock을 61초 전진하고 새 DB pool에서 같은 삭제를 회수한다.
                val work = work(factory, Clock.offset(clock, Duration.ofSeconds(61)))
                val retention = SpeakingAudioRetention(work, store) { fail(it) }
                val original = assertNotNull(old)
                val current = assertNotNull(retention.claim(710, original.audio.id))

                // 검증: 늦은 완료는 새 lease를 지우지 못하고 새 완료만 물리 삭제 상태를 확정한다.
                assertFalse(retention.complete(original))
                assertTrue(retention.complete(current))
                assertFalse(retention.complete(current))
                assertNotNull(work.read { records.audioById(710, original.audio.id)?.physicalDeletedAt })
            }
        }
    }

    @Test
    fun `물리 삭제 실패는 안전한 진단과 영속 대기를 남기고 재시도에서 같은 객체만 지운다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 파일 삭제 실패를 발생시키되 DB의 만료 대상과 객체 키는 고정한다.
                val work = work(factory)
                val session = seed(work, 712)
                val audio = work.write(712) {
                    records.saveAudio(
                        speakingAudioRecord(session, null, 0, "OPENING", byteArrayOf(1), "audio/wav", null)
                            .copy(retentionUntil = nowUtc.minusSeconds(1)),
                    )
                }
                val deleted = mutableListOf<String>()
                val failures = mutableListOf<String>()
                val store = object : SpeakingAudioStore {
                    override suspend fun put(key: String, bytes: ByteArray, contentType: String) = Unit
                    override suspend fun load(key: String) = byteArrayOf(1)
                    override suspend fun delete(key: String) {
                        deleted += key
                        if (deleted.size == 1) error("Synthetic storage failure")
                    }
                }

                // 실행: 일일 정리에서 실패한 대상은 회수 루프의 pendingOnly 검사로 다시 처리한다.
                assertEquals(0, SpeakingAudioRetention(work, store, failures::add).runOnce())
                assertEquals(0, SpeakingAudioRetention(work, store, failures::add).runOnce(pendingOnly = true))
                val later = work(factory, Clock.offset(clock, Duration.ofSeconds(61)))
                assertEquals(1, SpeakingAudioRetention(later, store, failures::add).runOnce(pendingOnly = true))

                // 검증: 새 만료 객체를 앞당겨 지우지 않고 기존 키의 삭제만 재시도한다.
                assertEquals(listOf(audio.objectKey, audio.objectKey), deleted)
                assertEquals(listOf("IllegalStateException"), failures)
                assertNotNull(later.read { records.audioById(712, audio.id)?.physicalDeletedAt })
            }
        }
    }

    @Test
    fun `만료 turn 소유권은 모델을 자동 호출하지 않고 수동 재시도 가능한 상태로 복구한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 음성이 저장된 PROCESSING turn에서 프로세스가 유실된 상태를 만든다.
                val work = work(factory)
                val session = seed(work, 709)
                val state = SpeakingTurnState(work)
                val grant = state.grant(709, session.id, SpeakingUploadRequest(1, "crash", 1, 1))
                val old = assertNotNull(state.claim(709, session.id, grant.id, grant.uploadToken, false))

                // 실행: DB lease보다 1초 뒤의 테스트 clock으로 회수한다.
                val expired = work(factory, Clock.offset(clock, Duration.ofSeconds(271)))
                val recovery = SpeakingTurnState(expired)
                assertEquals(1, recovery.recoverExpired())
                assertEquals(0, recovery.recoverExpired())

                // 검증: 늦은 이전 결과는 거부하고 사용자 재시도의 기존 1회 예산만 소비한다.
                val current = assertNotNull(expired.read { records.turn(session.id, grant.id) })
                assertEquals(SpeakingTurnStatus.FAILED, current.status)
                assertEquals("EXECUTION_LEASE_EXPIRED", current.errorCode)
                assertEquals(0, current.manualRetryCount)
                assertFalse(recovery.publish(old, SpeakingTurnContent(3.0, "Late synthetic speech."), null))
                val retried = assertNotNull(recovery.claim(709, session.id, grant.id, null, false, manualRetry = true))
                assertEquals(1, retried.turn.manualRetryCount)
            }
        }
    }

    @Test
    fun `시작 실패는 일일 집계와 키워드 선택에 남지 않고 성공 commit만 공개된다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 외부 opening 호출 전의 provisional을 실제 DB에 기록한다.
                val work = work(factory)
                val seed = seed(work, 706, SpeakingPracticeMode.FREE)
                val session = work.write(706) {
                    records.saveSession(
                        seed.copy(
                            snapshot = seed.snapshot.copy(
                                selectedKeywords = listOf(
                                    buildJsonObject {
                                        put("key", "synthetic"); put("text", "synthetic"); put("source", "USER")
                                        put("type", "WORD"); put("canonicalKey", "synthetic"); put(
                                        "selectionWeight", 1.0,
                                    )
                                    },
                                ),
                            ),
                            opening = buildJsonObject { put("_executionState", "PENDING") },
                        ),
                    )
                }
                val state = SpeakingOpeningState(work)
                val failed = assertNotNull(state.claim(706, session.id))

                // 실행: 동일 실행의 중복 claim을 막고 실패 후 같은 ID를 재시도한다.
                assertNull(state.claim(706, session.id))
                assertTrue(state.fail(failed, "CONVERSATION_GENERATION_FAILED"))
                work.read {
                    assertTrue(records.sessions(706, session.learningDate, session.learningDate).isEmpty())
                    assertNull(growth.mastery(706, "synthetic"))
                }
                val current = assertNotNull(state.claim(706, session.id))
                val response = buildJsonObject {
                    put("assistantText", "Synthetic opening.")
                    put("conversation", buildJsonObject { put("sessionSummary", "Synthetic summary.") })
                    put("usage", JsonObject(emptyMap()))
                }
                assertTrue(state.publish(current, response, null))
                assertFalse(state.publish(current, response, null))

                // 검증: 성공 결과만 한 번 집계되고 타 사용자는 provisional과 결과 모두 조회할 수 없다.
                work.read {
                    assertEquals(
                        listOf(session.id),
                        records.sessions(706, session.learningDate, session.learningDate).map { it.id },
                    )
                    assertEquals(1, growth.mastery(706, "synthetic")?.selectedCount)
                    assertNull(records.session(707, session.id))
                }
            }
        }
    }

    @Test
    fun `새 pool에서 만료 opening lease를 회수하면 이전 프로세스의 공개 결과를 거부한다`() = LocalScratchMysql.use { db ->
        var old: SpeakingOpeningClaim? = null
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비
                val work = work(factory)
                val seed = seed(work, 708, SpeakingPracticeMode.FREE)
                work.write(708) {
                    records.saveSession(
                        seed.copy(opening = buildJsonObject { put("_executionState", "PENDING") }),
                    )
                }
                old = assertNotNull(SpeakingOpeningState(work).claim(708, seed.id))
            }
        }
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 실행: 실제 3분 대기 대신 테스트 clock을 181초 전진해 재시작 후 fencing을 검사한다.
                val work = work(factory, Clock.offset(clock, Duration.ofSeconds(181)))
                val state = SpeakingOpeningState(work)
                val original = assertNotNull(old)
                val current = assertNotNull(state.claim(708, original.session.id))
                val response = buildJsonObject { put("assistantText", "Synthetic recovered opening.") }

                // 검증
                assertNotEquals(original.token, current.token)
                assertFalse(state.publish(original, response, null))
                assertFalse(state.fail(original, "LATE_FAILURE"))
                assertTrue(state.publish(current, response, null))
                assertTrue(assertNotNull(work.read { records.session(708, original.session.id) }).openingReady)
            }
        }
    }

    @Test
    fun `재녹음 실패와 오래된 revision은 기존 발화를 보존한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 실제 DB의 첫 발화를 완료하고 재녹음 허가만 갱신한다.
                val work = work(factory)
                val session = seed(work, 701)
                val turns = SpeakingTurnState(work)
                val grant = turns.grant(701, session.id, SpeakingUploadRequest(1, "first", 1, 1))
                val first = assertNotNull(turns.claim(701, session.id, grant.id, grant.uploadToken, false))
                val original = SpeakingTurnContent(
                    3.6, "Synthetic original speech.", .95, assistantText = "Synthetic next prompt.",
                )
                assertTrue(turns.publish(first, original, "Synthetic summary"))
                val renewed = turns.rerecordGrant(701, session.id, grant.id)
                val failed = assertNotNull(turns.claim(701, session.id, grant.id, renewed.uploadToken, true))

                // 실행: 실패 후 새 revision을 발급하고 이전 결과를 늦게 제출한다.
                assertTrue(turns.fail(failed, "STT", "STT_FAILED"))
                assertEquals(original, work.read { records.turn(session.id, grant.id)?.content })
                val current = turns.rerecordGrant(701, session.id, grant.id)
                assertFalse(turns.publish(failed, original.copy(transcript = "Late response"), null))
                val replacement = assertNotNull(turns.claim(701, session.id, grant.id, current.uploadToken, true))
                assertTrue(turns.publish(replacement, original.copy(durationSeconds = 6.3), null))

                // 검증: 실패 단계에서 원문은 유지되었고 성공한 교체만 시간을 바꾼다.
                work.read {
                    val stored = assertNotNull(records.turn(session.id, grant.id))
                    assertEquals("Synthetic original speech.", stored.content.transcript)
                    assertEquals(2, stored.recordingRevision)
                    assertEquals(1, records.session(701, session.id)?.completedTurns)
                    assertEquals(6L, records.session(701, session.id)?.totalDurationSeconds)
                    assertNull(records.session(702, session.id))
                }
                assertEquals(
                    "SESSION_NOT_FOUND",
                    assertFailsWith<SpeakingFailure> { turns.exclude(702, session.id, grant.id) }.code,
                )
            }
        }
    }

    @Test
    fun `문제 제출은 증거를 고정하고 녹음 제외와 교체를 막는다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비
                val work = work(factory)
                val session = seed(work, 703)
                val turns = SpeakingTurnState(work)
                repeat(2) { index ->
                    val grant =
                        turns.grant(703, session.id, SpeakingUploadRequest(index + 1, "attempt-$index", 1, index + 1))
                    val claim = assertNotNull(turns.claim(703, session.id, grant.id, grant.uploadToken, false))
                    assertTrue(
                        turns.publish(
                            claim,
                            SpeakingTurnContent(
                                4.0, "Synthetic reading.", .9,
                                assistantText = "Synthetic next prompt.",
                            ),
                            null,
                        ),
                    )
                }
                val state = SpeakingSessionState(work)

                // 실행: 같은 제출을 두 번 보내도 결과와 job은 하나다.
                val first = state.submitProblem(703, session.id, 1)
                val second = state.submitProblem(703, session.id, 1)

                // 검증
                assertEquals(first.id, second.id)
                val savedTurns = work.read { records.turns(session.id) }
                assertEquals(
                    "SESSION_NOT_ACTIVE",
                    assertFailsWith<SpeakingFailure> { turns.exclude(703, session.id, savedTurns.first().id) }.code,
                )
                assertEquals(
                    "TURN_PROCESSING",
                    assertFailsWith<SpeakingFailure> {
                        turns.rerecordGrant(
                            703, session.id, savedTurns.first().id,
                        )
                    }.code,
                )
                val snapshot = work.read { assertNotNull(records.job(session.id, 1)).request }
                assertEquals(2, snapshot.getValue("userTurns").jsonArray.size)
                assertEquals("READ_ALOUD_PROBLEM", snapshot.getValue("evaluationScope").jsonPrimitive.content)
                assertEquals(
                    "Synthetic script.",
                    snapshot.getValue(
                        "assistantTurns",
                    ).jsonArray.single().jsonObject["scriptText"]?.jsonPrimitive?.content,
                )
            }
        }
    }

    @Test
    fun `pool 재생성 후 lease 회수는 이전 결과를 거부하고 수동 재시도는 snapshot을 유지한다`() = LocalScratchMysql.use { db ->
        var sessionId = 0L
        var oldClaim: SpeakingEvaluationClaim? = null
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 자유 대화 완료가 코칭 intent만 생성하며 공식 점수는 만들지 않는다.
                val work = work(factory)
                val session = seed(work, 704, SpeakingPracticeMode.FREE)
                sessionId = session.id
                val state = SpeakingSessionState(work)
                assertEquals(SpeakingEvaluationStatus.NOT_REQUESTED, state.complete(704, sessionId).evaluationStatus)
                assertEquals(sessionId, state.complete(704, sessionId).id)
                assertNull(work.read { growth.activity(704, "SPEAKING", "ll-speaking-$sessionId")?.overallScore })
                oldClaim = assertNotNull(SpeakingEvaluationState(work).claim(704, sessionId, 0))
            }
        }
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 실행: 새 pool과 16분 전진한 clock으로 900초 lease를 회수한다.
                val work = work(factory, Clock.offset(clock, Duration.ofMinutes(16)))
                val state = SpeakingEvaluationState(work)
                val current = assertNotNull(state.claim(704, sessionId, 0))
                assertFalse(
                    state.complete(
                        assertNotNull(oldClaim), JsonObject(emptyMap()), { _, _ -> fail("늦은 결과 검증 호출") },
                    ) { _, _, _ ->
                        fail("늦은 결과 저장 호출")
                    },
                )
                assertTrue(state.fail(current))
                val retried = state.retry(704, sessionId, 0)

                // 검증: 원본의 immutable 증거·정책은 고정되고 request ID·manual attempt만 달라진다.
                assertEquals(current.job.sourceSnapshotHash, retried.sourceSnapshotHash)
                assertEquals(current.job.request["userTurns"], retried.request["userTurns"])
                assertEquals(1, retried.manualRetryCount)
                assertEquals(0, retried.recoveryCount)
                assertEquals(
                    "speaking-coaching-$sessionId-retry-1", retried.request["requestId"]?.jsonPrimitive?.content,
                )
                assertEquals(SpeakingSessionStatus.COMPLETED, work.read { records.session(704, sessionId)?.status })
            }
        }
    }

    @Test
    fun `평가 저장 예외는 결과와 job 완료를 함께 rollback한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비
                val work = work(factory)
                val session = seed(work, 705, SpeakingPracticeMode.GUIDED)
                SpeakingSessionState(work).complete(705, session.id)
                val state = SpeakingEvaluationState(work)
                val claim = assertNotNull(state.claim(705, session.id, 0))

                // 실행: 저장 후 후속 검증 실패를 발생시켜 실제 JDBC rollback을 확인한다.
                assertFailsWith<IllegalStateException> {
                    state.complete(claim, JsonObject(emptyMap()), { _, _ -> }) { value, job, response ->
                        records.saveResult(
                            SpeakingResultRecord(
                                value.id, job.problemIndex, job.resultKind, "EVALUATED", response, nowUtc,
                            ),
                        )
                        error("Synthetic apply failure")
                    }
                }

                // 검증
                work.read {
                    assertNull(records.result(session.id, 0))
                    assertEquals(SpeakingJobStatus.RUNNING, records.job(session.id, 0)?.status)
                }
                assertTrue(state.fail(claim))
                assertEquals(
                    "EVALUATION_FAILED",
                    work.read { growth.activity(705, "SPEAKING", "ll-speaking-${session.id}")?.status },
                )
            }
        }
    }

    private fun work(factory: DatabaseFactory, at: Clock = clock) =
        ExposedSpeakingUnitOfWork(JdbcTransactionRunner(factory.database, 4), at)

    private suspend fun seed(
        work: SpeakingUnitOfWork, userId: Long, mode: SpeakingPracticeMode = SpeakingPracticeMode.READ_ALOUD,
    ) = work.write(userId) {
        val coaching = mode == SpeakingPracticeMode.FREE
        val policy = SpeakingSessionPolicySnapshot.from(SettingsFixtures.admin())
        val snapshot = SpeakingSessionSnapshot(
            null, "Synthetic topic", "FREE_TALK", null, "Synthetic topic", null, null,
            emptyList(), "ko", "en", mode, ConversationStartMode.AI_FIRST, ConversationStartMode.AI_FIRST,
            CorrectionMode.CONVERSATION, 5, SpeakingSessionPolicy.maxTurns(mode, policy.maxTurns), "marin", "NORMAL",
            policy,
            null, if (coaching) SpeakingResultKind.SESSION_COACHING else SpeakingResultKind.SCORED_EVALUATION,
            if (coaching) "free-session-coaching-v1" else "speaking-evaluation-policy-v2",
        )
        records.saveSession(
            SpeakingSessionRecord(
                userId = userId, createIdempotencyKey = "seed", learningDate = LocalDate.parse("2026-09-26"),
                snapshot = snapshot, opening = buildJsonObject { put("assistantText", "Synthetic script.") },
                startedAt = nowUtc,
            ),
        )
    }
}
