package jp.co.translacat.languagelearning.features.growth.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.application.GrowthTransaction
import jp.co.translacat.languagelearning.features.growth.application.GrowthUnitOfWork
import jp.co.translacat.languagelearning.features.overview.application.LearningEvidenceQuery
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsResult
import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsSnapshot
import jp.co.translacat.languagelearning.features.speaking.SpeakingCoachingReadFixtures
import jp.co.translacat.languagelearning.features.speaking.application.SpeakingReadService
import jp.co.translacat.languagelearning.features.speaking.application.SpeakingReportService
import jp.co.translacat.languagelearning.features.speaking.application.SpeakingTransaction
import jp.co.translacat.languagelearning.features.speaking.application.SpeakingUnitOfWork
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingSessionStatus
import jp.co.translacat.languagelearning.features.speaking.infrastructure.ExposedSpeakingUnitOfWork
import jp.co.translacat.languagelearning.features.writing.application.WritingReportData
import jp.co.translacat.languagelearning.features.writing.application.WritingReportQueries
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import jp.co.translacat.languagelearning.support.SettingsFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 단일 JVM 실행의 관측값이며 시간·heap 차이에 성능 임계값을 부여하지 않는다. */
class GrowthPersistenceIntegrationTestEvidencePerformance {
    @Test
    fun `500개 원본에서 기존 전체 report와 제한된 근거 조회의 비용을 비교한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 기존 DTO로 읽을 수 있는 합성 snapshot과 별도 owner 원본을 작업 소유 DB에만 저장한다.
                val fixture = SpeakingCoachingReadFixtures()
                val day = fixture.now.toLocalDate()
                val clock = Clock.fixed(fixture.now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
                val transactions = JdbcTransactionRunner(factory.database, 2)
                val speaking = ExposedSpeakingUnitOfWork(transactions, clock)
                val ownerIds = speaking.write(41) {
                    (1..500).map { index ->
                        records.saveSession(fixture.session.copy(
                            id = 0, createIdempotencyKey = "evidence-performance-$index",
                            status = SpeakingSessionStatus.COMPLETED, completedAt = nowUtc,
                        )).id
                    }.toSet()
                }
                val other = speaking.write(42) {
                    records.saveSession(fixture.session.copy(
                        id = 0, userId = 42, createIdempotencyKey = "other-owner",
                        status = SpeakingSessionStatus.COMPLETED, completedAt = nowUtc,
                    ))
                }
                val settings = Proxy.newProxyInstance(
                    SettingsServiceOperations::class.java.classLoader, arrayOf(SettingsServiceOperations::class.java),
                ) { _, method, args ->
                    when (method.name) {
                        "userSnapshot" -> UserSettingsSnapshot(
                            args[0] as Long, day,
                            UserSettingsResult(
                                SettingsFixtures.configured(args[0] as Long).copy(learningLanguage = "en"),
                                SettingsFixtures.policy(),
                            ),
                        )
                        else -> error("Unexpected settings operation: ${method.name}")
                    }
                } as SettingsServiceOperations
                val legacyWork = CountedSpeakingWork(speaking)
                val report = SpeakingReportService(
                    legacyWork, settings, SpeakingReadService(legacyWork, settings),
                    evaluationView = { _, _ -> error("Report must not invoke an evaluation detail callback") },
                    writing = WritingReportQueries { WritingReportData(emptyList(), emptyList(), 0) },
                )
                val evidenceWork = CountedGrowthWork(ExposedGrowthUnitOfWork(transactions, clock))
                val query = LearningEvidenceQuery(evidenceWork)

                // 실행: 동일 DB에서 기존 report를 먼저, 새 첫 페이지를 다음에 한 번씩 읽는다. Provider는 구성하지 않는다.
                val legacy = observe { report.report(41, day, day) }
                val evidence = observe { query.execute(41, null, null, day, day, null, null, null, 25) }
                val legacyQueries = legacyWork.statements
                val evidenceQueries = evidenceWork.statements

                // 검증: SQL 수는 active learner 확인을 포함하며, 페이지는 중복·다른 owner를 포함하지 않는다.
                val legacyRows = legacy.result.getValue("history").jsonArray
                val rows = evidence.result.getValue("items").jsonArray
                val ids = rows.map { it.jsonObject.getValue("activityId").jsonPrimitive.content }
                val expectedIds = ownerIds.map { "SPEAKING:${LearningPublicId.encode(it)}" }.toSet()
                assertEquals(500, legacyRows.size)
                assertEquals(25, rows.size)
                assertEquals(25, ids.distinct().size)
                assertTrue(ids.all { it in expectedIds })
                assertEquals(2, evidenceQueries, "learner active check + one bounded metadata query")
                val second = query.execute(
                    41, null, null, day, day, null, null,
                    evidence.result.getValue("nextCursor").jsonPrimitive.content, 25,
                ).getValue("items").jsonArray
                assertEquals(25, second.size)
                assertTrue(second.none { it.jsonObject.getValue("activityId").jsonPrimitive.content in ids })
                val otherRows = query.execute(42, null, null, day, day, null, null, null, 25)
                    .getValue("items").jsonArray
                assertEquals(
                    listOf("SPEAKING:${LearningPublicId.encode(other.id)}"),
                    otherRows.map { it.jsonObject.getValue("activityId").jsonPrimitive.content },
                )

                // 단일 실행 heap delta는 할당량·peak 메모리가 아니며 GC/JIT/실행 순서 영향을 받는다.
                println(
                    "EVIDENCE_COMPARISON rows=500 page=25 legacyQueries=$legacyQueries evidenceQueries=$evidenceQueries " +
                        "legacyBytes=${legacy.bytes} evidenceBytes=${evidence.bytes} " +
                        "legacyMs=${legacy.nanos / 1_000_000.0} evidenceMs=${evidence.nanos / 1_000_000.0} " +
                        "legacyHeapDeltaBytes=${legacy.heapDelta} evidenceHeapDeltaBytes=${evidence.heapDelta} " +
                        "singleRun=true order=legacy_then_evidence settings=fixture providerCalls=0",
                )
            }
        }
    }

    private data class Observation(val result: JsonObject, val bytes: Int, val nanos: Long, val heapDelta: Long)

    private suspend fun observe(block: suspend () -> JsonObject): Observation {
        val runtime = Runtime.getRuntime()
        val beforeHeap = runtime.totalMemory() - runtime.freeMemory()
        val started = System.nanoTime()
        val result = block()
        val bytes = result.toString().toByteArray(Charsets.UTF_8).size
        val elapsed = System.nanoTime() - started
        return Observation(result, bytes, elapsed, runtime.totalMemory() - runtime.freeMemory() - beforeHeap)
    }

    private class CountedSpeakingWork(private val delegate: SpeakingUnitOfWork) : SpeakingUnitOfWork by delegate {
        var statements = 0

        override suspend fun <T> read(block: SpeakingTransaction.() -> T): T = delegate.read {
            val transaction = TransactionManager.current()
            val before = transaction.statementCount
            try {
                block()
            } finally {
                statements += transaction.statementCount - before
            }
        }
    }

    private class CountedGrowthWork(private val delegate: GrowthUnitOfWork) : GrowthUnitOfWork by delegate {
        var statements = 0

        override suspend fun <T> read(block: GrowthTransaction.() -> T): T = delegate.read {
            val transaction = TransactionManager.current()
            val before = transaction.statementCount
            try {
                block()
            } finally {
                statements += transaction.statementCount - before
            }
        }
    }
}
