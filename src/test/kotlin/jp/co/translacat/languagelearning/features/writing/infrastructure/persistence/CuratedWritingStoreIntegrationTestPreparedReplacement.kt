package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthProfile
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.*
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.*
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionResult
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.*
import kotlin.test.*

class CuratedWritingStoreIntegrationTestPreparedReplacement {
    private val clock = Clock.fixed(Instant.parse("2026-10-13T03:00:00Z"), ZoneOffset.UTC)
    private val owner = 73101L
    private val request = CuratedWritingStore.Start(owner, LocalDate.parse("2026-10-13"), WritingType.FREE,
        "ko", "ja", false, true, 7)

    private class Fixture(val runner: JdbcTransactionRunner, val generation: CuratedWritingGenerationStore,
                          val plans: CuratedWritingPlanStore, val original: JsonObject, val plan: JsonObject)

    @Test
    fun `미답안 교체는 원본 snapshot을 남기고 재시작과 같은 요청은 멱등이다`() = fixture { f ->
        // 준비: 학습 중인 원본과 동일 owner의 명시적 repair만 연결한다.
        val item = f.plan.getValue("items").jsonArray.single().jsonObject
        val itemId = LearningPublicId.decode(item.getValue("itemId").jsonPrimitive.content)
        val revision = item.getValue("contentRevision").jsonPrimitive.content
        val repaired = repair(f)

        // 실행: 응답 유실을 가정한 동일 요청은 이미 적용된 revision을 재사용한다.
        val applied = f.plans.replacePrepared(request, itemId, "repair", revision, 1)
        val manifest = CuratedWritingPlanFixtures.manifest(3)
        val restarted = CuratedWritingPlanStore(f.runner, CuratedWritingStore(f.runner, manifest, clock), manifest.releaseId, clock = clock)
        val replay = restarted.replacePrepared(request, itemId, "repair", revision, 1)
        assertEquals(applied, replay)
        val next = applied.getValue("items").jsonArray.single().jsonObject

        // 검증: 이전 원문·정답은 archive에 있고 새 답안에는 새 revision만 연결된다.
        assertEquals(2, applied.getValue("planRevision").jsonPrimitive.int)
        assertEquals(1, applied.getValue("replacementCount").jsonPrimitive.int)
        assertEquals(repaired.getValue("catalogId"), next.getValue("catalogId"))
        assertNotEquals(revision, next.getValue("contentRevision").jsonPrimitive.content)
        assertNull(next["reference"])
        assertEquals("repair", next.getValue("preparationRequestKey").jsonPrimitive.content)
        f.runner.read {
            val archive = CuratedWritingReplacementTable.selectAll().single()
            val snapshot = Json.parseToJsonElement(archive[CuratedWritingReplacementTable.originalSnapshotJson]).jsonObject
            assertEquals(item.getValue("catalogId"), snapshot.getValue("catalogId"))
            assertEquals(item.getValue("originText"), snapshot.getValue("public").jsonObject.getValue("originText"))
            assertTrue(snapshot.getValue("private").jsonObject.getValue("referenceAnswers").jsonArray.isNotEmpty())
            assertEquals(0L, CuratedAnswersTable.selectAll().count())
            assertEquals(2L, CuratedGenerationTable.selectAll().count())
        }
        assertEquals(f.original.getValue("catalogId"), f.generation.get(owner, "original").getValue("catalogId"))
        val stale = assertFailsWith<IllegalArgumentException> { f.plans.submit(request, itemId, "私の答えです。", revision) }
        assertEquals("WRITING_ITEM_STALE", stale.message)
        val submitted = f.plans.submit(request, itemId, "私は毎週図書館を利用します。", next.getValue("contentRevision").jsonPrimitive.content)
        assertEquals(1, submitted.getValue("submittedItemCount").jsonPrimitive.int)
        assertNotNull(submitted.getValue("items").jsonArray.single().jsonObject["reference"])
        assertEquals(submitted, f.plans.replacePrepared(request, itemId, "repair", revision, 1))
        f.plans.delete(request, LearningPublicId.decode(f.plan.getValue("dailySetId").jsonPrimitive.content), 2)
        f.runner.read {
            assertEquals(0L, CuratedWritingReplacementTable.selectAll().count())
            assertEquals(2L, CuratedGenerationTable.selectAll().count())
        }
    }

    @Test
    fun `다른 owner 언어 policy revision과 공급 중 교체는 모두 원본을 보존한다`() = fixture { f ->
        // 준비
        val item = f.plan.getValue("items").jsonArray.single().jsonObject
        val itemId = LearningPublicId.decode(item.getValue("itemId").jsonPrimitive.content)
        val setId = LearningPublicId.decode(f.plan.getValue("dailySetId").jsonPrimitive.content)
        val revision = item.getValue("contentRevision").jsonPrimitive.content
        repair(f)

        // 실행 / 검증: 거절은 archive도 만들지 않으며 원본을 수정하지 않는다.
        assertEquals("WRITING_ITEM_NOT_FOUND", assertFailsWith<IllegalStateException> {
            f.plans.replacePrepared(request.copy(userId = owner + 1), itemId, "repair", revision, 1)
        }.message)
        for ((context, expected) in listOf(request.copy(originLanguage = "en") to "WRITING_LANGUAGE_CONFLICT",
            request.copy(qaOnly = false) to "WRITING_POLICY_CONFLICT")) {
            assertEquals(expected, assertFailsWith<IllegalArgumentException> {
                f.plans.replacePrepared(context, itemId, "repair", revision, 1)
            }.message)
        }
        assertEquals("WRITING_PLAN_STALE", assertFailsWith<IllegalArgumentException> {
            f.plans.replacePrepared(request, itemId, "repair", revision, 2)
        }.message)
        assertEquals("WRITING_ITEM_STALE", assertFailsWith<IllegalArgumentException> {
            f.plans.replacePrepared(request, itemId, "repair", "0".repeat(64), 1)
        }.message)
        assertEquals("WRITING_PREPARATION_CONFLICT", assertFailsWith<IllegalStateException> {
            f.plans.replacePrepared(request, itemId, "original", revision, 1)
        }.message)
        f.plans.claimSupply(request, setId, 1)
        assertEquals("WRITING_SUPPLY_BUSY", assertFailsWith<IllegalArgumentException> {
            f.plans.replacePrepared(request, itemId, "repair", revision, 1)
        }.message)
        f.runner.read {
            assertEquals(0L, CuratedWritingReplacementTable.selectAll().count())
            assertEquals(revision, CuratedItemsTable.selectAll().single()[CuratedItemsTable.contentRevision])
        }
    }

    @Test
    fun `답안이 있는 문항은 교체하지 않으며 명시적 계획 삭제는 archive까지 삭제한다`() = fixture { f ->
        // 준비
        val item = f.plan.getValue("items").jsonArray.single().jsonObject
        val itemId = LearningPublicId.decode(item.getValue("itemId").jsonPrimitive.content)
        val setId = LearningPublicId.decode(f.plan.getValue("dailySetId").jsonPrimitive.content)
        val revision = item.getValue("contentRevision").jsonPrimitive.content
        repair(f)

        // 실행 / 검증: 저장 답안과 공식 결과를 손대지 않는다.
        f.plans.submit(request, itemId, "私は図書館を使います。", revision)
        assertEquals("WRITING_ANSWER_NOT_ALLOWED", assertFailsWith<IllegalArgumentException> {
            f.plans.replacePrepared(request, itemId, "repair", revision, 1)
        }.message)
        f.runner.read {
            assertEquals(1L, CuratedAnswersTable.selectAll().count())
            assertEquals(0L, CuratedWritingReplacementTable.selectAll().count())
        }
        f.plans.delete(request, setId, 1)
        assertEquals("WRITING_ITEM_NOT_FOUND", assertFailsWith<IllegalStateException> {
            f.plans.replacePrepared(request, itemId, "repair", revision, 1)
        }.message)
        f.runner.read { assertEquals(2L, CuratedGenerationTable.selectAll().count()) }
    }

    private suspend fun repair(f: Fixture): JsonObject {
        f.generation.prepare(owner, "repair", WritingType.FREE, 3, "library-use", "original", "조건을 더 명확하게 표현해 주세요.")
        val content = CuratedWritingGenerationPolicyTest.content(WritingType.FREE)
        f.generation.saveOutput(owner, "repair", output(JsonObject(content + ("prompt" to JsonPrimitive("도서관 이용 경험과 이유를 자유롭게 써 주세요.")))))
        return f.generation.finish(owner, "repair")
    }

    private fun output(value: JsonObject) = ModelExecutionResult(value, 10, 20, "openai", "gpt-5.6-luna")

    private fun fixture(block: suspend (Fixture) -> Unit) = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory -> runBlocking {
            val runner = JdbcTransactionRunner(factory.database, 4)
            val manifest = CuratedWritingPlanFixtures.manifest(3)
            val snapshots = CuratedWritingStore(runner, manifest, clock)
            snapshots.importManifest()
            ExposedWritingSetUnitOfWork(runner, clock).write(owner) {
                growth.saveProfile(GrowthProfile(userId = owner, state = "ACTIVE", baseLevelScore = 60.0, createdAt = nowUtc, updatedAt = nowUtc))
            }
            val generation = CuratedWritingGenerationStore(runner, snapshots, manifest.releaseId, clock)
            generation.prepare(owner, "original", WritingType.FREE, 3, "library-use")
            generation.saveOutput(owner, "original", output(CuratedWritingGenerationPolicyTest.content(WritingType.FREE)))
            val original = generation.finish(owner, "original")
            val plans = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId, clock = clock)
            val plan = plans.start(request.copy(candidateIds = setOf(original.getValue("catalogId").jsonPrimitive.content)), 1, false)
            block(Fixture(runner, generation, plans, original, plan))
        } }
    }
}
