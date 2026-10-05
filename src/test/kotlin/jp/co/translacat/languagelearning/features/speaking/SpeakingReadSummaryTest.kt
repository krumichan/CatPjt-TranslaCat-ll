package jp.co.translacat.languagelearning.features.speaking

import jp.co.translacat.languagelearning.features.growth.domain.repository.GrowthRepository
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.speaking.application.*
import jp.co.translacat.languagelearning.features.speaking.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.lang.reflect.Proxy
import kotlin.test.*

class SpeakingReadSummaryTest {
    @Test
    fun `원본 근거가 없는 완료 요약과 코칭 결과가 없는 완료 요약은 숨긴다`() = runBlocking {
        // 준비: DB·모델 호출 없는 읽기 저장소로 legacy와 no-job 코칭의 상태 경계를 검사한다.
        val fixture = SpeakingCoachingReadFixtures()
        var current = fixture.session
        val repository = Proxy.newProxyInstance(
            SpeakingRepository::class.java.classLoader, arrayOf(SpeakingRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "session" -> current
                "turns" -> fixture.turns
                "result", "job", "audio" -> null
                else -> error("Unexpected repository operation: ${method.name}")
            }
        } as SpeakingRepository
        val transaction = object : SpeakingTransaction {
            override val records = repository
            override val nowUtc = fixture.now
            override val growth: GrowthRepository get() = error("No growth write during read")
        }
        val work = object : SpeakingUnitOfWork {
            override suspend fun <T> read(block: SpeakingTransaction.() -> T): T = transaction.block()
            override suspend fun <T> write(userId: Long, block: SpeakingTransaction.() -> T): T =
                error("No write during session read")
            override suspend fun <T> catalogWrite(block: SpeakingTransaction.() -> T): T =
                error("No catalog write during session read")
        }
        val settings = Proxy.newProxyInstance(
            SettingsServiceOperations::class.java.classLoader, arrayOf(SettingsServiceOperations::class.java),
        ) { _, _, _ -> error("No settings write during session read") } as SettingsServiceOperations
        val service = SpeakingReadService(work, settings)

        // 실행·검증: 저장 원문·summary를 수정하지 않고 완료 응답의 캐시 노출만 막는다.
        for (kind in SpeakingResultKind.entries) {
            current = fixture.session.copy(snapshot = fixture.session.snapshot.copy(
                resultKind = kind,
                resultPolicyVersion = if (kind == SpeakingResultKind.SESSION_COACHING)
                    "free-session-coaching-v1" else "speaking-evaluation-policy-v2",
            ))
            assertEquals(JsonPrimitive(current.sessionSummary), service.session(41, current.id)["sessionSummary"])
            current = current.copy(status = SpeakingSessionStatus.COMPLETED, completedAt = fixture.now)
            val response = service.session(41, current.id)
            assertEquals(JsonNull, response["sessionSummary"])
            val scored = kind == SpeakingResultKind.SCORED_EVALUATION
            assertEquals(if (scored) "UNAVAILABLE" else "UNVERIFIED",
                response.getValue("summaryEvidenceAvailability").jsonPrimitive.content)
            assertEquals(JsonArray(listOf(JsonPrimitive(if (scored)
                "SOURCE_EVIDENCE_CHANGED_OR_UNAVAILABLE" else "SUMMARY_SOURCE_PROVENANCE_UNAVAILABLE"))),
                response["summaryEvidenceLimitations"])
            assertEquals(fixture.session.sessionSummary, current.sessionSummary)
        }
    }
}
