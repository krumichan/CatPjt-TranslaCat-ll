package jp.co.translacat.languagelearning.features.speaking

import jp.co.translacat.languagelearning.features.settings.application.DefaultSettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.application.GetAdminSettings
import jp.co.translacat.languagelearning.features.settings.application.UpdateUserSettings
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettingsChange
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.ExposedAdminSettingsUnitOfWork
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.ExposedSettingsReadQueries
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.ExposedSettingsUnitOfWork
import jp.co.translacat.languagelearning.features.speaking.application.SpeakingReadService
import jp.co.translacat.languagelearning.features.speaking.application.SpeakingReportService
import jp.co.translacat.languagelearning.features.speaking.domain.*
import jp.co.translacat.languagelearning.features.speaking.infrastructure.ExposedSpeakingUnitOfWork
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.ExposedWritingReportQueries
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import jp.co.translacat.languagelearning.support.SettingsFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpeakingStateIntegrationTestReports {
    @Test
    fun `실제 원본 평가로 통계 이력과 최소근거 추천을 읽고 다른 사용자는 분리한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 실제 설정·영속 세션·평가·발화에 두 공식 활동과 합성 도움 근거를 저장한다.
                val clock = Clock.fixed(Instant.parse("2026-09-26T04:00:00Z"), ZoneOffset.UTC)
                val runner = JdbcTransactionRunner(factory.database, 4)
                val settingsWork = ExposedSettingsUnitOfWork(runner, clock)
                val settings = DefaultSettingsServiceOperations(
                    settingsWork, ExposedSettingsReadQueries(runner),
                    GetAdminSettings(ExposedAdminSettingsUnitOfWork(runner)), clock,
                )
                UpdateUserSettings(settingsWork).execute(
                    731, UserSettingsChange(originLanguage = "ko", learningLanguage = "en"),
                )
                UpdateUserSettings(settingsWork).execute(
                    732, UserSettingsChange(originLanguage = "ko", learningLanguage = "en"),
                )
                val work = ExposedSpeakingUnitOfWork(runner, clock)
                val sessions = work.write(731) {
                    val policy = SpeakingSessionPolicySnapshot.from(SettingsFixtures.admin())
                    val snapshot = SpeakingSessionSnapshot(
                        null, "Synthetic topic", "FREE_TALK", null, null, null, null,
                        emptyList(), "ko", "en", SpeakingPracticeMode.GUIDED, ConversationStartMode.AI_FIRST,
                        ConversationStartMode.AI_FIRST, CorrectionMode.CONVERSATION, 5, 10, "marin", "NORMAL", policy,
                        null, SpeakingResultKind.SCORED_EVALUATION, "speaking-evaluation-policy-v2",
                    )
                    (0..1).map { index ->
                        val session = records.saveSession(
                            SpeakingSessionRecord(
                                userId = 731, createIdempotencyKey = "report-$index",
                                learningDate = nowUtc.toLocalDate().minusDays(index.toLong()), snapshot = snapshot,
                                status = SpeakingSessionStatus.EVALUATED,
                                evaluationStatus = SpeakingEvaluationStatus.EVALUATED,
                                completedTurns = 2, totalDurationSeconds = 60,
                                startedAt = nowUtc.minusDays(index.toLong()), completedAt = nowUtc,
                            ),
                        )
                        records.saveTurn(
                            SpeakingTurnRecord(
                                sessionId = session.id, turnIndex = 1, idempotencyKey = "synthetic",
                                uploadToken = "synthetic", uploadExpiresAt = nowUtc,
                                content = SpeakingTurnContent(assistanceUsage = listOf(SpeakingAssistanceType.HINT)),
                            ),
                        )
                        records.saveResult(
                            SpeakingResultRecord(
                                session.id, 0, SpeakingResultKind.SCORED_EVALUATION, "EVALUATED",
                                buildJsonObject {
                                    put("overallScore", 80)
                                    put("evaluationConfidence", .9)
                                    put(
                                        "metrics",
                                        JsonArray(
                                            listOf(
                                                buildJsonObject {
                                                    put("type", "FLUENCY"); put("state", "EVALUATED"); put(
                                                    "score", 75.0,
                                                )
                                                },
                                            ),
                                        ),
                                    )
                                    put(
                                        "profileSignals",
                                        JsonArray(
                                            listOf(
                                                buildJsonObject {
                                                    put("patternKey", "synthetic grammar"); put(
                                                    "direction", "WEAKNESS",
                                                ); put("metricType", "GRAMMAR")
                                                    put("recommendedFocus", "합성 문법 연습"); put("confidence", .9)
                                                },
                                            ),
                                        ),
                                    )
                                    put("eligibility", buildJsonObject { put("validSttTurnRatio", .8) })
                                },
                                nowUtc.minusDays(index.toLong()),
                            ),
                        )
                        session
                    }
                }
                val reads = SpeakingReadService(work, settings)
                val reports = SpeakingReportService(
                    work, settings, reads, { _, _ -> JsonNull },
                    ExposedWritingReportQueries(runner),
                )

                // 실행: 실제 DB에서 화면 통계·원본 평가 사실·이력 및 추천 문맥을 조회한다.
                val result = reports.report(731, null, null)
                val history = reports.history(731, sessions.first().id)
                val focus = reports.recommendedFocus(731)

                // 검증: 기간별 시간·점수·현재일과 최소 두 근거를 보존하고 음성 도움 근거를 전달한다.
                assertEquals(2, result.getValue("summary").jsonObject.getValue("sessions").jsonPrimitive.int)
                assertEquals(2.0, result.getValue("summary").jsonObject.getValue("totalMinutes").jsonPrimitive.double)
                assertEquals(
                    75.0, result.getValue("summary").jsonObject.getValue("fluencyAverage").jsonPrimitive.double,
                )
                assertEquals(1, result.getValue("today").jsonObject.getValue("completedSessions").jsonPrimitive.int)
                assertEquals(2, result.getValue("evaluations").jsonArray.size)
                assertEquals(
                    JsonArray(listOf(JsonPrimitive("HINT"))),
                    result.getValue("evaluations").jsonArray.first().jsonObject["assistanceUsage"],
                )
                assertEquals(JsonPrimitive(-sessions.first().id), history.getValue("session").jsonObject["id"])
                assertEquals(listOf("합성 문법 연습"), focus)
                assertTrue(reports.report(732, null, null).getValue("history").jsonArray.isEmpty())
                assertTrue(reports.recommendedFocus(732).isEmpty())
                assertFailsWith<SpeakingFailure> { reports.history(732, sessions.first().id) }
            }
        }
    }
}
