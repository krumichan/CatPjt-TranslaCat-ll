package jp.co.translacat.languagelearning.features.speaking

import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsResult
import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsSnapshot
import jp.co.translacat.languagelearning.features.speaking.application.SpeakingReadService
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

class SpeakingStateIntegrationTestReadEvidence {
    @Test
    fun `코칭 이력은 현재 owner와 원본을 대조하고 만료 음성과 삭제 인용을 숨긴다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 이번 검사가 소유한 scratch DB에 코칭 원본·결과와 만료 직전/직후 음성만 넣는다.
                val baseline = SpeakingCoachingReadFixtures()
                val clock = Clock.fixed(baseline.now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
                val work = ExposedSpeakingUnitOfWork(JdbcTransactionRunner(factory.database, 4), clock)
                val session = work.write(41) { records.saveSession(baseline.session.copy(id = 0)) }
                val fixture = SpeakingCoachingReadFixtures(session.id)
                val assistantAudio = work.write(41) {
                    fixture.turns.forEach {
                        assertEquals(it.id, records.saveTurn(it.copy(id = 0)).id)
                    }
                    records.saveJob(fixture.job.copy(status = SpeakingJobStatus.SUCCEEDED))
                    records.saveResult(fixture.result)
                    fun audio(turnId: Long?, role: String, valid: Boolean) = SpeakingAudioRecord(
                        sessionId = session.id, turnId = turnId, role = role, recordingRevision = 1,
                        objectKey = "synthetic-$role", contentType = "audio/wav", fileName = null,
                        byteLength = 1, sha256 = "a".repeat(64),
                        retentionUntil = if (valid) nowUtc.plusSeconds(1) else nowUtc,
                    )
                    records.saveAudio(audio(null, "OPENING", false))
                    records.saveAudio(audio(1, "USER", false))
                    records.saveAudio(audio(1, "ASSISTANT", true))
                }
                var settingsCalls = 0
                val settings = Proxy.newProxyInstance(
                    SettingsServiceOperations::class.java.classLoader, arrayOf(SettingsServiceOperations::class.java),
                ) { _, method, args ->
                    settingsCalls++
                    when (method.name) {
                        "userSnapshot" -> UserSettingsSnapshot(
                            args[0] as Long, fixture.now.toLocalDate(),
                            UserSettingsResult(SettingsFixtures.configured(41), SettingsFixtures.policy()),
                        )
                        "adminPolicy" -> SettingsFixtures.admin()
                        else -> error("Unexpected settings operation: ${method.name}")
                    }
                }
                    as SettingsServiceOperations
                val service = SpeakingReadService(work, settings)
                suspend fun assertSummaryAbsent() {
                    assertEquals(JsonNull, service.session(41, session.id)["sessionSummary"])
                    assertEquals(JsonNull, service.detail(41, session.id).getValue("session").jsonObject["sessionSummary"])
                    assertEquals(JsonNull, service.historyPayload(41, session.id)
                        .getValue("session").jsonObject["sessionSummary"])
                }

                // 실행: 이력 재진입은 생성·평가 또는 설정 변경을 호출하지 않는 읽기 경로다.
                val first = service.historyPayload(41, session.id)
                val repeated = service.historyPayload(41, session.id)

                // 검증: 만료 시각과 동일하면 이미 재생 불가이며 다른 owner는 원문에 접근하지 못한다.
                assertEquals(first, repeated)
                assertEquals(0, settingsCalls)
                assertEquals("AVAILABLE", first.getValue("coachingResult").jsonObject
                    .getValue("evidenceAvailability").jsonPrimitive.content)
                assertEquals(JsonNull, first.getValue("session").jsonObject["openingAssistantAudioUrl"])
                val firstTurn = first.getValue("turns").jsonArray.first().jsonObject
                assertEquals(JsonNull, firstTurn["userAudioUrl"])
                assertNotEquals(JsonNull, firstTurn["assistantAudioUrl"])
                assertEquals(JsonPrimitive(fixture.session.sessionSummary), service.session(41, session.id)["sessionSummary"])
                assertEquals(JsonPrimitive(fixture.session.sessionSummary), service.detail(41, session.id)
                    .getValue("session").jsonObject["sessionSummary"])
                assertEquals("SESSION_NOT_FOUND", assertFailsWith<SpeakingFailure> {
                    service.historyPayload(42, session.id)
                }.code)

                // 실행·검증: 인용이 없는 turn 변경도 전체 대화 요약을 세 경로에서 숨긴다.
                val firstOnly = fixture.result.copy(response = JsonObject(fixture.result.response + (
                    "items" to JsonArray(fixture.result.response.getValue("items").jsonArray.take(1))
                )))
                work.write(41) {
                    records.saveResult(firstOnly)
                    records.saveTurn(fixture.turns.last().copy(content = fixture.turns.last().content.copy(
                        transcript = "Changed unquoted source",
                    )))
                }
                assertSummaryAbsent()
                work.write(41) {
                    records.saveTurn(fixture.turns.last())
                    records.saveResult(fixture.result)
                }

                // 실행: 현재 제외 상태와 오디오 revision 변경은 저장 코칭을 수정하지 않고 조회에 전파한다.
                work.write(41) {
                    records.saveTurn(fixture.turns.first().copy(excludedFromEvaluation = true))
                    records.saveAudio(assistantAudio.copy(recordingRevision = 2))
                }
                val limited = service.historyPayload(41, session.id)
                val coaching = limited.getValue("coachingResult").jsonObject
                assertEquals("LIMITED", coaching.getValue("evidenceAvailability").jsonPrimitive.content)
                assertEquals(1, coaching.getValue("items").jsonArray.size)
                assertEquals("GROUNDED", coaching.getValue("contentStatus").jsonPrimitive.content)
                assertEquals(JsonNull, limited.getValue("turns").jsonArray.first().jsonObject["assistantAudioUrl"])
                assertSummaryAbsent()

                // 실행: 이 테스트의 두 번째 발화 원본만 삭제하여 저장 JSON 인용의 재노출을 방지한다.
                db.connect().use { connection ->
                    connection.prepareStatement(
                        "DELETE FROM language_learning_speaking_turn WHERE session_id=? AND id=2",
                    ).use { statement ->
                        statement.setLong(1, session.id)
                        assertEquals(1, statement.executeUpdate())
                    }
                }
                val unavailable = service.historyPayload(41, session.id).getValue("coachingResult").jsonObject

                // 검증: 원본이 사라진 인용은 반환하지 않으며 저장된 판정과 공식 점수는 건드리지 않는다.
                assertEquals("UNAVAILABLE", unavailable.getValue("evidenceAvailability").jsonPrimitive.content)
                assertTrue(unavailable.getValue("items").jsonArray.isEmpty())
                assertSummaryAbsent()

                // 실행·검증: 코칭 items가 빈 경우에도 삭제된 전체 원본의 요약을 되살리지 않는다.
                val emptyResult = fixture.result.copy(response = JsonObject(fixture.result.response + mapOf(
                    "items" to JsonArray(emptyList()), "contentStatus" to JsonPrimitive("NO_USABLE_EVIDENCE"),
                )))
                work.write(41) { records.saveResult(emptyResult) }
                assertSummaryAbsent()
                work.read {
                    assertEquals(emptyResult.response, records.result(session.id, 0)?.response)
                    assertEquals(SpeakingJobStatus.SUCCEEDED, records.job(session.id, 0)?.status)
                    assertEquals(SpeakingEvaluationStatus.NOT_REQUESTED, records.session(41, session.id)?.evaluationStatus)
                    assertTrue(growth.activities(41, null, fixture.now.toLocalDate(), fixture.now.toLocalDate(), 0, 10).isEmpty())
                }

                // 준비: 원본 turn과 job이 모두 없는 legacy 요약의 진행 중/완료 상태를 같은 scratch DB에서 비교한다.
                val legacy = work.write(41) {
                    records.saveSession(fixture.session.copy(
                        id = 0, createIdempotencyKey = "legacy-summary",
                        snapshot = fixture.session.snapshot.copy(
                            resultKind = SpeakingResultKind.SCORED_EVALUATION,
                            resultPolicyVersion = "speaking-evaluation-policy-v2",
                        ),
                    ))
                }

                // 검증: 진행 중 대화 요약은 보존하되 보관 원본조차 없는 완료 요약은 모든 읽기 경로에서 숨긴다.
                assertEquals(JsonPrimitive(legacy.sessionSummary), service.session(41, legacy.id)["sessionSummary"])
                assertEquals("UNVERIFIED", service.session(41, legacy.id)
                    .getValue("summaryEvidenceAvailability").jsonPrimitive.content)
                work.write(41) {
                    records.saveSession(legacy.copy(status = SpeakingSessionStatus.COMPLETED, completedAt = nowUtc))
                }
                assertEquals(JsonNull, service.session(41, legacy.id)["sessionSummary"])
                assertEquals(JsonNull, service.detail(41, legacy.id).getValue("session").jsonObject["sessionSummary"])
                assertEquals(JsonNull, service.historyPayload(41, legacy.id)
                    .getValue("session").jsonObject["sessionSummary"])

                // 검증: 완료된 신규 코칭에 job이 누락되어도 빈 근거를 정상 summary로 해석하지 않는다.
                work.write(41) {
                    records.saveSession(legacy.copy(
                        status = SpeakingSessionStatus.COMPLETED, completedAt = nowUtc,
                        snapshot = fixture.session.snapshot,
                    ))
                }
                assertEquals(JsonNull, service.session(41, legacy.id)["sessionSummary"])
                assertEquals(JsonNull, service.historyPayload(41, legacy.id)
                    .getValue("session").jsonObject["sessionSummary"])
            }
        }
    }
}
