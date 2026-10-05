package jp.co.translacat.languagelearning.features.speaking

import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsResult
import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsSnapshot
import jp.co.translacat.languagelearning.features.speaking.application.*
import jp.co.translacat.languagelearning.features.speaking.domain.*
import jp.co.translacat.languagelearning.features.speaking.infrastructure.ExposedSpeakingUnitOfWork
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import jp.co.translacat.languagelearning.support.SettingsFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.ZoneOffset
import kotlin.test.*

class SpeakingStateIntegrationTestScoredSummary {
    @Test
    fun `실제 저장된 점수형 세 모드 요약은 현재 원본과 일치할 때 세 조회 경로에서 보존한다`() =
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { factory ->
                runBlocking {
                    // 준비: 실제 저장 계약으로 세션·turn·SESSION 평가 요청을 만들고 점수형 상태를 보존한다.
                    val fixture = SpeakingCoachingReadFixtures()
                    val work = ExposedSpeakingUnitOfWork(JdbcTransactionRunner(factory.database, 4),
                        Clock.fixed(fixture.now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC))
                    val service = SpeakingReadService(work, settings(fixture))
                    for (mode in SpeakingPracticeMode.entries) {
                        val session = seed(work, fixture, mode, true)
                        val initialJob = work.read { records.job(session.id, 0) }

                        // 실행·검증: 조회와 재진입이 저장 요청·요약·점수 상태를 변경하지 않는다.
                        val first = service.historyPayload(41, session.id)
                        assertEquals(first, service.historyPayload(41, session.id))
                        assertSummary(service, session, JsonPrimitive(session.sessionSummary), "AVAILABLE")
                        assertEquals("SESSION_NOT_FOUND", assertFailsWith<SpeakingFailure> {
                            service.historyPayload(42, session.id)
                        }.code)
                        assertEquals(initialJob, work.read { records.job(session.id, 0) })
                        assertEquals(session, work.read { records.session(41, session.id) })

                        // 실행: 재녹음 revision만 달라져도 과거 요약의 원본 계약은 더 이상 일치하지 않는다.
                        work.write(41) {
                            val original = records.turns(session.id).first()
                            records.saveTurn(original.copy(recordingRevision = original.recordingRevision + 1))
                        }

                        // 검증: 응답만 제한하고 원래 저장 요약과 공식 평가 상태를 유지한다.
                        assertSummary(service, session, JsonNull, "UNAVAILABLE")
                        assertEquals(session.sessionSummary, work.read { records.session(41, session.id)?.sessionSummary })
                        assertEquals(SpeakingEvaluationStatus.EVALUATED,
                            work.read { records.session(41, session.id)?.evaluationStatus })
                    }
                }
            }
        }

    @Test
    fun `실제 legacy 원본은 남았지만 요청이 없을 때 제한 표시를 유지하고 제외 원본은 숨긴다`() =
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { factory ->
                runBlocking {
                    // 준비: 알려진 점수 정책의 정상 완료 원본만 보관된 legacy 세션이다.
                    val fixture = SpeakingCoachingReadFixtures()
                    val work = ExposedSpeakingUnitOfWork(JdbcTransactionRunner(factory.database, 4),
                        Clock.fixed(fixture.now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC))
                    val service = SpeakingReadService(work, settings(fixture))
                    for (mode in SpeakingPracticeMode.entries) {
                        val session = seed(work, fixture, mode, false)

                        // 실행·검증: 코칭 필드 부재 때문에 숨기지 않고 원본 검증 한계도 그대로 알린다.
                        assertSummary(service, session, JsonPrimitive(session.sessionSummary), "UNVERIFIED")
                        assertNull(work.read { records.job(session.id, 0) })
                        work.write(41) {
                            val original = records.turns(session.id).first()
                            records.saveTurn(original.copy(excludedFromEvaluation = true, status = SpeakingTurnStatus.EXCLUDED))
                        }
                        assertSummary(service, session, JsonNull, "UNAVAILABLE")
                    }
                }
            }
        }

    private suspend fun seed(
        work: SpeakingUnitOfWork, fixture: SpeakingCoachingReadFixtures, mode: SpeakingPracticeMode, withJob: Boolean,
    ): SpeakingSessionRecord = work.write(41) {
        val session = records.saveSession(fixture.session.copy(
            id = 0, createIdempotencyKey = "scored-summary-${mode.name}",
            completedTurns = fixture.turns.size, completedAt = nowUtc,
            status = SpeakingSessionStatus.EVALUATED, evaluationStatus = SpeakingEvaluationStatus.EVALUATED,
            snapshot = fixture.session.snapshot.copy(
                practiceMode = mode, resultKind = SpeakingResultKind.SCORED_EVALUATION,
                resultPolicyVersion = "speaking-evaluation-policy-v2",
            ),
        ))
        fixture.turns.forEach { records.saveTurn(it.copy(id = 0, sessionId = session.id)) }
        if (withJob) records.saveJob(SpeakingJobRecord(
            sessionId = session.id, problemIndex = 0, resultKind = session.snapshot.resultKind,
            resultPolicyVersion = session.snapshot.resultPolicyVersion, sourceSnapshotHash = null,
            request = speakingEvaluationRequest(session, 0), availableAt = nowUtc, status = SpeakingJobStatus.SUCCEEDED,
        ))
        session
    }

    private suspend fun assertSummary(
        service: SpeakingReadService, session: SpeakingSessionRecord, expected: JsonElement, availability: String,
    ) {
        val responses = listOf(
            service.session(41, session.id),
            service.detail(41, session.id).getValue("session").jsonObject,
            service.historyPayload(41, session.id).getValue("session").jsonObject,
        )
        responses.forEach {
            assertEquals(expected, it["sessionSummary"], session.snapshot.practiceMode.name)
            assertEquals(availability, it.getValue("summaryEvidenceAvailability").jsonPrimitive.content)
            assertEquals("SCORED_EVALUATION", it.getValue("resultKind").jsonPrimitive.content)
            assertEquals("EVALUATED", it.getValue("evaluationStatus").jsonPrimitive.content)
        }
    }

    private fun settings(fixture: SpeakingCoachingReadFixtures) = Proxy.newProxyInstance(
        SettingsServiceOperations::class.java.classLoader, arrayOf(SettingsServiceOperations::class.java),
    ) { _, method, args ->
        when (method.name) {
            "userSnapshot" -> UserSettingsSnapshot(args[0] as Long, fixture.now.toLocalDate(),
                UserSettingsResult(SettingsFixtures.configured(41), SettingsFixtures.policy()))
            "adminPolicy" -> SettingsFixtures.admin()
            else -> error("Unexpected settings operation: ${method.name}")
        }
    } as SettingsServiceOperations
}
