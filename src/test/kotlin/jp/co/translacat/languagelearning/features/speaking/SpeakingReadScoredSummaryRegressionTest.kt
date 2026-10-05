package jp.co.translacat.languagelearning.features.speaking

import jp.co.translacat.languagelearning.features.growth.domain.repository.GrowthRepository
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.speaking.application.*
import jp.co.translacat.languagelearning.features.speaking.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.lang.reflect.Proxy
import kotlin.test.*

class SpeakingReadScoredSummaryRegressionTest {
    @Test
    fun `정상 완료된 세 점수형 모드는 코칭 결과가 없어도 저장 원본과 일치하는 요약을 표시한다`() = runBlocking {
        // 준비: 기존 점수형 계약의 저장 요청·원본을 제공하고 코칭 결과와 모델 호출은 제공하지 않는다.
        val fixture = SpeakingCoachingReadFixtures()
        for (mode in SpeakingPracticeMode.entries) {
            val (session, job) = scoredSource(fixture, mode)
            val service = readOnlyService(session, fixture.turns, job, fixture)

            // 실행: session과 history 경로가 같은 보관 원본을 조회한다.
            val response = service.session(session.userId, session.id)
            val history = service.historyPayload(session.userId, session.id)

            // 검증: 코칭 전용 필드가 없다는 사실은 점수형 요약 은닉 조건이 아니다.
            assertEquals(JsonPrimitive(session.sessionSummary), response["sessionSummary"], mode.name)
            assertEquals(response, history.getValue("session"))
            assertEquals(JsonNull, history["coachingResult"])
            assertEquals("SCORED_EVALUATION", response.getValue("resultKind").jsonPrimitive.content)
            assertEquals("AVAILABLE", response.getValue("summaryEvidenceAvailability").jsonPrimitive.content)
            assertEquals("SESSION_NOT_FOUND", assertFailsWith<SpeakingFailure> {
                service.session(session.userId + 1, session.id)
            }.code)
        }
    }

    @Test
    fun `점수형의 삭제 재녹음 제외 도움말 질문 변경은 저장된 요약을 다시 노출하지 않는다`() = runBlocking {
        // 준비: 정상 완료 원본에 각 변경 하나만 적용한다.
        val fixture = SpeakingCoachingReadFixtures()
        val (session, job) = scoredSource(fixture, SpeakingPracticeMode.GUIDED)
        val first = fixture.turns.first()
        val variations = listOf(
            fixture.turns.dropLast(1),
            listOf(first.copy(recordingRevision = 2), fixture.turns.last()),
            listOf(first.copy(content = first.content.copy(transcript = "Changed transcript")), fixture.turns.last()),
            listOf(first.copy(excludedFromEvaluation = true), fixture.turns.last()),
            listOf(first.copy(content = first.content.copy(assistanceUsage = emptyList())), fixture.turns.last()),
            listOf(first.copy(content = first.content.copy(assistantText = "Changed question")), fixture.turns.last()),
            listOf(first.copy(sessionId = session.id + 1), fixture.turns.last()),
        )

        // 실행·검증: 인용 가능한 일부 turn이 남아도 전체 대화 요약의 원본 일치를 추측하지 않는다.
        for (turns in variations) {
            val response = readOnlyService(session, turns, job, fixture).session(session.userId, session.id)
            assertEquals(JsonNull, response["sessionSummary"])
            assertEquals("UNAVAILABLE", response.getValue("summaryEvidenceAvailability").jsonPrimitive.content)
        }
        val mismatched = listOf(
            job.copy(sessionId = session.id + 1),
            job.copy(resultPolicyVersion = "unknown-scoring-policy"),
            job.copy(request = JsonObject(job.request + ("learningLanguage" to JsonPrimitive("ja")))),
            job.copy(request = JsonObject(job.request + ("sessionSummary" to JsonPrimitive("Other summary")))),
            job.copy(request = JsonObject(job.request + ("userTurns" to JsonArray(List(2) {
                job.request.getValue("userTurns").jsonArray.first()
            })))),
        )
        for (changed in mismatched) {
            assertEquals(JsonNull, readOnlyService(session, fixture.turns, changed, fixture)
                .session(session.userId, session.id)["sessionSummary"])
        }
    }

    @Test
    fun `저장 요청 없는 정상 legacy는 제한을 유지하며 표시하고 미상 정책과 원본 누락은 숨긴다`() = runBlocking {
        // 준비: 세 점수형 모드 모두 과거 대화 원본은 남아 있으나 평가 요청만 없는 경우다.
        val fixture = SpeakingCoachingReadFixtures()
        for (mode in SpeakingPracticeMode.entries) {
            val (session, _) = scoredSource(fixture, mode)

            // 실행·검증: 과거 표시를 복구하되 provenance 확인 완료라고 승격하지 않는다.
            val legacy = readOnlyService(session, fixture.turns, null, fixture).session(session.userId, session.id)
            assertEquals(JsonPrimitive(session.sessionSummary), legacy["sessionSummary"])
            assertEquals("UNVERIFIED", legacy.getValue("summaryEvidenceAvailability").jsonPrimitive.content)
            assertEquals(JsonArray(listOf(JsonPrimitive("SUMMARY_SOURCE_PROVENANCE_UNAVAILABLE"))),
                legacy["summaryEvidenceLimitations"])
            assertEquals(JsonNull, readOnlyService(session, fixture.turns.dropLast(1), null, fixture)
                .session(session.userId, session.id)["sessionSummary"])
            val unknown = session.copy(snapshot = session.snapshot.copy(resultPolicyVersion = "unknown-policy"))
            for (status in listOf(SpeakingSessionStatus.IN_PROGRESS, SpeakingSessionStatus.EVALUATED)) {
                val response = readOnlyService(unknown.copy(status = status), fixture.turns, null, fixture)
                    .session(session.userId, session.id)
                assertEquals(JsonNull, response["sessionSummary"])
                assertEquals(JsonArray(listOf(JsonPrimitive("UNKNOWN_RESULT_POLICY"))), response["summaryEvidenceLimitations"])
            }
        }
    }

    @Test
    fun `평가 실패와 대기 상태는 원본이 일치하는 대화 요약과 별도로 유지한다`() = runBlocking {
        // 준비: 요약은 대화에서 저장한 원문이며 평가의 성공 결과가 아니다.
        val fixture = SpeakingCoachingReadFixtures()
        val (session, job) = scoredSource(fixture, SpeakingPracticeMode.FREE)
        for ((status, evaluation) in listOf(
            SpeakingSessionStatus.EVALUATION_FAILED to SpeakingEvaluationStatus.FAILED,
            SpeakingSessionStatus.EVALUATING to SpeakingEvaluationStatus.EVALUATING,
            SpeakingSessionStatus.EVALUATED to SpeakingEvaluationStatus.INSUFFICIENT_EVIDENCE,
        )) {
            val changed = session.copy(status = status, evaluationStatus = evaluation)

            // 실행·검증: 원본 읽기로 점수·평가상태·성장 데이터를 만들거나 성공 상태로 바꾸지 않는다.
            val response = readOnlyService(changed, fixture.turns, job, fixture).session(changed.userId, changed.id)
            assertEquals(JsonPrimitive(changed.sessionSummary), response["sessionSummary"])
            assertEquals(evaluation.name, response.getValue("evaluationStatus").jsonPrimitive.content)
            assertEquals(status.name, response.getValue("status").jsonPrimitive.content)
        }
    }

    private fun scoredSource(
        fixture: SpeakingCoachingReadFixtures, mode: SpeakingPracticeMode,
    ): Pair<SpeakingSessionRecord, SpeakingJobRecord> {
        val session = fixture.session.copy(
            status = SpeakingSessionStatus.EVALUATED,
            evaluationStatus = SpeakingEvaluationStatus.EVALUATED,
            completedAt = fixture.now, completedTurns = fixture.turns.size,
            snapshot = fixture.session.snapshot.copy(
                practiceMode = mode, resultKind = SpeakingResultKind.SCORED_EVALUATION,
                resultPolicyVersion = "speaking-evaluation-policy-v2",
            ),
        )
        val request = JsonObject(fixture.job.request.filterKeys {
            it !in setOf("resultKind", "resultPolicyVersion", "sourceSnapshotHash")
        } + mapOf(
            "practiceMode" to JsonPrimitive(mode.name),
            "evaluationScope" to JsonPrimitive("SESSION"),
            "evaluationPolicyVersion" to JsonPrimitive("speaking-evaluation-policy-v2"),
            "originLanguage" to JsonPrimitive(session.snapshot.originLanguage),
            "learningLanguage" to JsonPrimitive(session.snapshot.learningLanguage),
            "sessionSummary" to JsonPrimitive(checkNotNull(session.sessionSummary)),
        ))
        return session to fixture.job.copy(
            resultKind = SpeakingResultKind.SCORED_EVALUATION,
            resultPolicyVersion = "speaking-evaluation-policy-v2", sourceSnapshotHash = null,
            request = request, status = SpeakingJobStatus.SUCCEEDED,
        )
    }

    private fun readOnlyService(
        session: SpeakingSessionRecord, turns: List<SpeakingTurnRecord>, job: SpeakingJobRecord?,
        fixture: SpeakingCoachingReadFixtures,
    ): SpeakingReadService {
        val records = Proxy.newProxyInstance(
            SpeakingRepository::class.java.classLoader, arrayOf(SpeakingRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "session" -> session.takeIf { args[0] == session.userId && args[1] == session.id }
                "turns" -> turns
                "job" -> job
                "result", "audio" -> null
                else -> error("Unexpected repository operation: ${method.name}")
            }
        } as SpeakingRepository
        val transaction = object : SpeakingTransaction {
            override val records = records
            override val nowUtc = fixture.now
            override val growth: GrowthRepository get() = error("No growth mutation during summary read")
        }
        val work = object : SpeakingUnitOfWork {
            override suspend fun <T> read(block: SpeakingTransaction.() -> T): T = transaction.block()
            override suspend fun <T> write(userId: Long, block: SpeakingTransaction.() -> T): T =
                error("No write during session/history read")
            override suspend fun <T> catalogWrite(block: SpeakingTransaction.() -> T): T =
                error("No catalog write during session/history read")
        }
        val settings = Proxy.newProxyInstance(
            SettingsServiceOperations::class.java.classLoader, arrayOf(SettingsServiceOperations::class.java),
        ) { _, _, _ -> error("No settings call during session/history read") } as SettingsServiceOperations
        return SpeakingReadService(work, settings)
    }
}
