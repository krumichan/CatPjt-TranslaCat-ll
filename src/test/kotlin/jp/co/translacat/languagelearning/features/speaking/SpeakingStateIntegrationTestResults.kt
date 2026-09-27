package jp.co.translacat.languagelearning.features.speaking

import jp.co.translacat.languagelearning.features.speaking.application.SpeakingEvaluationState
import jp.co.translacat.languagelearning.features.speaking.application.SpeakingResultApplication
import jp.co.translacat.languagelearning.features.speaking.application.SpeakingSessionState
import jp.co.translacat.languagelearning.features.speaking.application.SpeakingUnitOfWork
import jp.co.translacat.languagelearning.features.speaking.domain.*
import jp.co.translacat.languagelearning.features.speaking.infrastructure.ExposedSpeakingUnitOfWork
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import jp.co.translacat.languagelearning.support.SettingsFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*

class SpeakingStateIntegrationTestResults {
    private val clock = Clock.fixed(Instant.parse("2026-09-26T04:00:00Z"), ZoneOffset.UTC)
    private val axes = listOf(
        "GRAMMAR", "VOCABULARY", "NATURALNESS", "MEANING", "EXPRESSIVENESS", "FLUENCY", "PRONUNCIATION", "INTERACTION",
    )

    @Test
    fun `공식 결과와 Growth는 같은 DB에 한 번 반영하고 기존 도움말 가중치를 보존한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 실제 제출 발화 두 개의 ID와 SAMPLE_ANSWER 기록을 고정한다.
                val work = work(factory)
                val session = seed(work, 721, SpeakingPracticeMode.GUIDED)
                SpeakingSessionState(work).complete(721, session.id)
                val claim = assertNotNull(SpeakingEvaluationState(work).claim(721, session.id, 0))
                val result = response(claim.job.request, .9, true)
                val application = SpeakingResultApplication(work)

                // 실행: 같은 lease 결과를 중복 전달해도 첫 성공 이후에는 반영하지 않는다.
                assertTrue(application.complete(claim, result))
                assertFalse(application.complete(claim, result))

                // 검증: 원본8지표를 저장하고 공식 evidence 횟수·가중치 및 공개 DTO를 확인한다.
                work.read {
                    assertEquals(SpeakingJobStatus.SUCCEEDED, records.job(session.id, 0)?.status)
                    assertEquals("EVALUATED", records.result(session.id, 0)?.status)
                    val activity = assertNotNull(growth.activity(721, "SPEAKING", "ll-speaking-${session.id}"))
                    assertEquals(80.0, activity.overallScore)
                    assertEquals(8, growth.metrics(activity.id).size)
                    val evidence = growth.evidenceList(721, "SPEAKING", 10).single()
                    assertEquals(1, evidence.evidenceCount)
                    assertEquals(.432, evidence.weightedEvidence, .000001)
                }
                val view = assertNotNull(application.evaluation(721, session.id))
                assertEquals(JsonPrimitive(-session.id), view["sessionId"])
                assertTrue(view.getValue("strengthsJson").jsonPrimitive.isString)
                assertEquals(8, view.getValue("metrics").jsonArray.size)
                assertFailsWith<SpeakingFailure> { application.evaluation(722, session.id) }
            }
        }
    }

    @Test
    fun `낮은 원본 confidence의 평가 결과는 보존하되 공식 metric과 evidence를 늘리지 않는다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비
                val work = work(factory)
                val session = seed(work, 723, SpeakingPracticeMode.GUIDED)
                SpeakingSessionState(work).complete(723, session.id)
                val claim = assertNotNull(SpeakingEvaluationState(work).claim(723, session.id, 0))

                // 실행: Core 원본의 공식 평가 confidence 0.70 미만 경계를 적용한다.
                assertTrue(SpeakingResultApplication(work).complete(claim, response(claim.job.request, .69, true)))

                // 검증: 정상 종결과 원본 지표 보관은 유지하며 공식 성장만 분리한다.
                work.read {
                    val result = assertNotNull(records.result(session.id, 0))
                    assertEquals("INSUFFICIENT_EVIDENCE", result.status)
                    assertEquals(JsonNull, result.response["overallScore"])
                    assertEquals(8, result.response.getValue("metrics").jsonArray.size)
                    val activity = assertNotNull(growth.activity(723, "SPEAKING", "ll-speaking-${session.id}"))
                    assertEquals("INSUFFICIENT_EVIDENCE", activity.status)
                    assertTrue(growth.metrics(activity.id).isEmpty())
                    assertTrue(growth.evidenceList(723, "SPEAKING", 10).isEmpty())
                    assertEquals(SpeakingSessionStatus.EVALUATED, records.session(723, session.id)?.status)
                }
            }
        }
    }

    @Test
    fun `코칭 snapshot 불일치는 rollback하고 정상 FREE 코칭은 공식 Growth를 만들지 않는다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비
                val work = work(factory)
                val session = seed(work, 724, SpeakingPracticeMode.FREE)
                SpeakingSessionState(work).complete(724, session.id)
                val claim = assertNotNull(SpeakingEvaluationState(work).claim(724, session.id, 0))
                val request = claim.job.request
                val response = buildJsonObject {
                    listOf("requestId", "sessionId", "resultKind", "resultPolicyVersion", "sourceSnapshotHash")
                        .forEach { put(it, request.getValue(it)) }
                    put("schemaVersion", "free-session-coaching-v1")
                    put("contentStatus", "NO_USABLE_EVIDENCE")
                    put("items", JsonArray(emptyList()))
                    put("limitationReasons", JsonArray(listOf(JsonPrimitive("INSUFFICIENT_EVIDENCE"))))
                    put("promptVersion", "synthetic")
                }
                val application = SpeakingResultApplication(work)

                // 실행: source hash가 바뀐 응답은 job 완료와 결과 쓰기 모두 거부한다.
                assertFailsWith<IllegalArgumentException> {
                    application.complete(
                        claim, JsonObject(response + ("sourceSnapshotHash" to JsonPrimitive("different"))),
                    )
                }
                work.read {
                    assertNull(records.result(session.id, 0))
                    assertEquals(SpeakingJobStatus.RUNNING, records.job(session.id, 0)?.status)
                }
                assertTrue(application.complete(claim, response))
                assertFalse(application.complete(claim, response))

                // 검증: 코칭은 완료 활동만 유지하고 점수·metric·profile evidence를 만들지 않는다.
                work.read {
                    assertEquals(
                        SpeakingEvaluationStatus.NOT_REQUESTED, records.session(724, session.id)?.evaluationStatus,
                    )
                    val activity = assertNotNull(growth.activity(724, "SPEAKING", "ll-speaking-${session.id}"))
                    assertEquals("COMPLETED", activity.status)
                    assertNull(activity.overallScore)
                    assertNull(activity.evaluationConfidence)
                    assertTrue(growth.metrics(activity.id).isEmpty())
                    assertTrue(growth.evidenceList(724, "SPEAKING", 10).isEmpty())
                }
                assertNull(application.evaluation(724, session.id))
            }
        }
    }

    @Test
    fun `READ_ALOUD 문제 결과는 제출 메타데이터를 보존하고 공식 성장을 별도 세션 job에 남긴다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 실제 두 발화를 첫 문제로 제출해 snapshot을 고정한다.
                val work = work(factory)
                val session = seed(work, 725, SpeakingPracticeMode.READ_ALOUD)
                val submitted = SpeakingSessionState(work).submitProblem(725, session.id, 1)
                val claim = assertNotNull(SpeakingEvaluationState(work).claim(725, session.id, 1))
                val response = response(claim.job.request, .9, false)

                // 실행
                assertTrue(SpeakingResultApplication(work).complete(claim, response))

                // 검증: 문제 평가 자체는 Activity나 공식 metric을 생성하지 않는다.
                work.read {
                    val result = assertNotNull(records.result(session.id, 1))
                    assertEquals(submitted.response["submittedAt"], result.response["submittedAt"])
                    assertEquals(JsonPrimitive(2), result.response["attemptCount"])
                    assertEquals("EVALUATED", result.status)
                    assertNull(growth.activity(725, "SPEAKING", "ll-speaking-${session.id}"))
                    assertEquals(SpeakingSessionStatus.IN_PROGRESS, records.session(725, session.id)?.status)
                    assertNull(records.job(session.id, 0))
                }
            }
        }
    }

    private fun response(request: JsonObject, confidence: Double, profile: Boolean): JsonObject = buildJsonObject {
        val turns = request.getValue("userTurns").jsonArray.map { it.jsonObject.getValue("turnId") }
        put("requestId", request.getValue("requestId")); put("sessionId", request.getValue("sessionId"))
        put("status", "EVALUATED"); put("overallScore", 80); put("evaluationConfidence", confidence)
        put(
            "metrics",
            JsonArray(
                axes.map { axis ->
                    buildJsonObject {
                        put("type", axis); put("state", if (axis == "MEANING") "EVALUATED" else "NOT_EVALUABLE")
                        put("score", if (axis == "MEANING") JsonPrimitive(80.0) else JsonNull)
                        put("confidence", .9); put("summary", "Synthetic feedback.")
                        put("notEvaluableReason", if (axis == "MEANING") JsonNull else JsonPrimitive("NO_EVIDENCE"))
                        put(
                            "evidence",
                            if (axis == "MEANING") JsonArray(
                                listOf(buildJsonObject { put("turnId", turns.first()) }),
                            ) else JsonArray(emptyList()),
                        )
                    }
                },
            ),
        )
        listOf("strengths", "improvements", "recommendedExpressions", "pronunciationPractice").forEach {
            put(
                it, JsonArray(emptyList()),
            )
        }
        put(
            "profileSignals",
            if (!profile) JsonArray(emptyList()) else JsonArray(
                listOf(
                    buildJsonObject {
                        put("metricType", "MEANING"); put("patternKey", "Synthetic pattern"); put(
                        "direction", "WEAKNESS",
                    )
                        put("confidence", .9); put("recommendedFocus", "Synthetic focus"); put(
                        "evidenceTurnIds", JsonArray(turns),
                    )
                    },
                ),
            ),
        )
        put(
            "eligibility",
            buildJsonObject {
                put("validUserTurns", turns.size); put("validUserSpeechSeconds", 30.0); put("validSttTurnRatio", .8)
                put("eligibleBeforeAi", true); put("missingRequirements", JsonArray(emptyList()))
            },
        )
        put("evaluationVersion", "synthetic-v1"); put("scoringPolicyVersion", "synthetic-policy"); put(
        "promptVersion", "synthetic-prompt",
    )
        put("evaluatedAxes", JsonArray(listOf(JsonPrimitive("MEANING")))); put("evaluationCoverage", .1)
        put("evidencePolicyVersion", "speaking-transcript-evidence-v2"); put("evidenceSource", "TRANSCRIPT_OBSERVATION")
    }

    private fun work(factory: DatabaseFactory) =
        ExposedSpeakingUnitOfWork(JdbcTransactionRunner(factory.database, 4), clock)

    private suspend fun seed(work: SpeakingUnitOfWork, userId: Long, mode: SpeakingPracticeMode) = work.write(userId) {
        // 준비: 일반 사용자 데이터와 분리된 scratch catalog에 세션·실제 발화 두 개를 기록한다.
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
        val session = records.saveSession(
            SpeakingSessionRecord(
                userId = userId, createIdempotencyKey = "seed", learningDate = LocalDate.parse("2026-09-26"),
                snapshot = snapshot, opening = buildJsonObject { put("assistantText", "Synthetic script.") },
                startedAt = nowUtc,
            ),
        )
        repeat(2) { index ->
            records.saveTurn(
                SpeakingTurnRecord(
                    sessionId = session.id, turnIndex = index + 1, idempotencyKey = "turn-$index",
                    problemIndex = if (mode == SpeakingPracticeMode.READ_ALOUD) 1 else null, attemptIndex = index + 1,
                    recordingRevision = 1, status = SpeakingTurnStatus.READY, uploadToken = "synthetic",
                    uploadExpiresAt = nowUtc.plusMinutes(1),
                    content = SpeakingTurnContent(
                        durationSeconds = 15.0, transcript = "Synthetic speech $index.", sttConfidence = .9,
                        assistantText = "Synthetic next prompt.",
                        assistanceUsage = listOf(SpeakingAssistanceType.SAMPLE_ANSWER),
                    ),
                ),
            )
        }
        session
    }
}
