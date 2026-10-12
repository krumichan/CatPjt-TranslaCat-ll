package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthProfile
import jp.co.translacat.languagelearning.features.writing.application.CuratedWritingGenerationService
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.*
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.*
import jp.co.translacat.languagelearning.shared.ai.*
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.*
import kotlin.test.*

class CuratedWritingStoreIntegrationTestGeneration {
    private val clock = Clock.fixed(Instant.parse("2026-10-11T03:00:00Z"), ZoneOffset.UTC)
    private val manifest = CuratedWritingPlanFixtures.manifest(3)
    private fun result(type: WritingType) = ModelExecutionResult(CuratedWritingGenerationPolicyTest.content(type), 120, 240, "openai", "gpt-5.6-luna")

    @Test
    fun `v1 원본은 보존하고 같은 소유자의 국소 수정은 한 번만 새 DRAFT로 저장한다`() = withDatabase { runner ->
        // 준비: 과거 정책의 저장 결과와 요청 hash를 가진 독립 scratch 행이다.
        val snapshots = CuratedWritingStore(runner, manifest, clock)
        val store = CuratedWritingGenerationStore(runner, snapshots, manifest.releaseId, clock)
        store.prepare(71006, "original", WritingType.FREE, 3, "library-plan")
        store.saveOutput(71006, "original", result(WritingType.FREE))
        store.finish(71006, "original")
        runner.write {
            val identity = buildJsonObject {
                put("writingType", "FREE"); put("targetBand", 3); put("topic", "library-plan")
                put("releaseId", manifest.releaseId); put("policyVersion", "curated-writing-generation-v1")
            }
            CuratedGenerationTable.update({ CuratedGenerationTable.userId eq 71006 }) {
                it[policyVersion] = "curated-writing-generation-v1"
                it[requestHash] = CuratedWritingManifestCodec.sha256(CuratedWritingManifestCodec.canonical(identity))
            }
        }
        val original = store.get(71006, "original")
        var calls = 0
        val service = CuratedWritingGenerationService(store, ModelExecutionPort { command ->
            calls++
            val input = Json.parseToJsonElement(command.messages.single().content).jsonObject
            assertEquals(result(WritingType.FREE).output, input["repairCandidateContent"])
            assertEquals("한국어 설명만 바로잡으세요.", input["repairNotes"]?.jsonPrimitive?.content)
            result(WritingType.FREE)
        }, clock)

        // 실행: 과거 성공 요청은 새 정책에서도 재과금하지 않고, 명시 수정은 별도 ID로 저장한다.
        assertEquals(original, service.prepare(71006, "original", WritingType.FREE, 3, "library-plan"))
        val repaired = service.prepare(71006, "repair", WritingType.FREE, 3, "library-plan", "original", "한국어 설명만 바로잡으세요.")
        assertEquals(repaired, service.prepare(71006, "repair", WritingType.FREE, 3, "library-plan", "original", "한국어 설명만 바로잡으세요."))

        // 검증: 소유자/문맥 불일치와 두 번째 수정은 호출 전 거절하고 원본 결과를 보존한다.
        assertEquals("SUCCEEDED", repaired["status"]?.jsonPrimitive?.content)
        assertNotEquals(original["catalogId"], repaired["catalogId"])
        assertEquals(original, store.get(71006, "original"))
        assertFailsWith<IllegalStateException> {
            service.prepare(71007, "foreign", WritingType.FREE, 3, "library-plan", "original", "한국어 설명만 바로잡으세요.")
        }
        assertFailsWith<IllegalArgumentException> {
            service.prepare(71006, "wrong-context", WritingType.FREE, 4, "library-plan", "original", "한국어 설명만 바로잡으세요.")
        }
        assertFailsWith<IllegalArgumentException> {
            service.prepare(71006, "second-repair", WritingType.FREE, 3, "library-plan", "original", "다시 수정하세요.")
        }
        assertFailsWith<IllegalArgumentException> {
            service.prepare(71006, "repair-chain", WritingType.FREE, 3, "library-plan", "repair", "다시 수정하세요.")
        }
        assertEquals(1, calls)
        runner.read {
            assertEquals(2L, CuratedGenerationTable.selectAll().count())
            assertEquals(2L, CuratedCatalogTable.selectAll().count())
        }
    }

    @Test
    fun `신규 생성은 중복 요청과 재시작 후에도 한 DRAFT를 선택하며 참고 답안을 숨긴다`() = withDatabase { runner ->
        // 준비
        var calls = 0
        val snapshots = CuratedWritingStore(runner, manifest, clock)
        snapshots.importManifest()
        ExposedWritingSetUnitOfWork(runner, clock).write(71001) {
            growth.saveProfile(GrowthProfile(userId = 71001, state = "ACTIVE", baseLevelScore = 60.0,
                createdAt = nowUtc, updatedAt = nowUtc))
        }
        val service = CuratedWritingGenerationService(CuratedWritingGenerationStore(runner, snapshots, manifest.releaseId, clock),
            ModelExecutionPort { command ->
                calls++
                assertEquals(ModelTier.LUNA, command.tier)
                assertEquals("LANGUAGE_LEARNING_CURATED_WRITING_GENERATION", command.taskName)
                result(WritingType.FREE)
            }, clock)

        // 실행: 같은 요청과 새로운 저장소 인스턴스의 조회는 추가 모델 호출을 만들지 않는다.
        val first = service.prepare(71001, "same-key", WritingType.FREE, 3, "library-plan")
        assertEquals(first, service.prepare(71001, "same-key", WritingType.FREE, 3, "library-plan"))
        val restartedSnapshots = CuratedWritingStore(runner, manifest, clock)
        val restarted = CuratedWritingGenerationService(CuratedWritingGenerationStore(runner, restartedSnapshots, manifest.releaseId, clock),
            ModelExecutionPort { error("조회에 모델을 호출하면 안 됩니다") }, clock)
        assertEquals(first, restarted.get(71001, "same-key"))
        val id = first.getValue("catalogId").jsonPrimitive.content
        val plans = CuratedWritingPlanStore(runner, restartedSnapshots, manifest.releaseId, clock = clock)
        val plan = plans.start(CuratedWritingStore.Start(71001, LocalDate.parse("2026-10-11"), WritingType.FREE,
            "ko", "ja", false, true, 7, setOf(id)), 1, false)

        // 검증: 기존 manifest 밖의 소유 DRAFT를 실제 계획에 복사하고 공개 요구만 노출한다.
        assertEquals(1, calls)
        assertEquals("SUCCEEDED", first.getValue("status").jsonPrimitive.content)
        val item = plan.getValue("items").jsonArray.single().jsonObject
        assertEquals(id, item.getValue("catalogId").jsonPrimitive.content)
        assertTrue(item.getValue("taskRequirements").jsonArray.isNotEmpty())
        assertNull(item["reference"])
        runner.read {
            assertFalse(restartedSnapshots.eligible("ko", "ja", WritingType.FREE, 71002).any { it.id == id })
            assertEquals(1L, CuratedGenerationTable.selectAll().count())
        }
        assertFailsWith<IllegalStateException> { restarted.get(71002, "same-key") }
        assertFailsWith<IllegalArgumentException> { service.prepare(71001, "same-key", WritingType.FREE, 4, "library-plan") }
    }

    @Test
    fun `콘텐츠 저장 뒤 catalog 복구와 중복 callback은 재과금하지 않는다`() = withDatabase { runner ->
        // 준비: 모델 결과 저장 후 프로세스가 끝난 상태를 재현한다.
        val snapshots = CuratedWritingStore(runner, manifest, clock)
        val store = CuratedWritingGenerationStore(runner, snapshots, manifest.releaseId, clock)
        store.prepare(71003, "lost-response", WritingType.GUIDED, 3, "library-plan")
        store.saveOutput(71003, "lost-response", result(WritingType.GUIDED))
        store.saveOutput(71003, "lost-response", result(WritingType.GUIDED))

        // 실행
        val restarted = CuratedWritingGenerationService(CuratedWritingGenerationStore(runner, snapshots, manifest.releaseId, clock),
            ModelExecutionPort { error("저장 결과를 재사용해야 합니다") }, clock)
        val response = restarted.prepare(71003, "lost-response", WritingType.GUIDED, 3, "library-plan")

        // 검증
        assertEquals("SUCCEEDED", response.getValue("status").jsonPrimitive.content)
        assertEquals(response, restarted.prepare(71003, "lost-response", WritingType.GUIDED, 3, "library-plan"))
        runner.read { assertEquals(1L, CuratedCatalogTable.selectAll().count()) }
    }

    @Test
    fun `잘못된 외부 ID는 실패 원본으로 남고 같은 요청을 재호출하지 않는다`() = withDatabase { runner ->
        // 준비
        var calls = 0
        val snapshots = CuratedWritingStore(runner, manifest, clock)
        val service = CuratedWritingGenerationService(CuratedWritingGenerationStore(runner, snapshots, manifest.releaseId, clock),
            ModelExecutionPort {
                calls++
                result(WritingType.FREE).copy(output = JsonObject(CuratedWritingGenerationPolicyTest.content(WritingType.FREE) +
                    ("ownerId" to JsonPrimitive(999))))
            }, clock)

        // 실행 / 검증
        val failed = service.prepare(71004, "invalid", WritingType.FREE, 3, "library-plan")
        assertEquals("FAILED", failed.getValue("status").jsonPrimitive.content)
        assertEquals(failed, service.prepare(71004, "invalid", WritingType.FREE, 3, "library-plan"))
        assertEquals(1, calls)
        runner.read {
            assertEquals(0L, CuratedCatalogTable.selectAll().count())
            assertNotNull(CuratedGenerationTable.selectAll().single()[CuratedGenerationTable.outputJson])
        }
    }

    @Test
    fun `전송 불명과 결과 없는 실행은 자동 재생성하지 않는다`() = withDatabase { runner ->
        // 준비
        var calls = 0
        val snapshots = CuratedWritingStore(runner, manifest, clock)
        val store = CuratedWritingGenerationStore(runner, snapshots, manifest.releaseId, clock)
        val service = CuratedWritingGenerationService(store, ModelExecutionPort {
            calls++
            throw ModelExecutionFailure("MODEL_EXECUTION_TRANSPORT", 503, true)
        }, clock)

        // 실행 / 검증
        val uncertain = service.prepare(71005, "uncertain", WritingType.TRANSLATION, 3, "library-plan")
        assertEquals("UNCERTAIN", uncertain.getValue("status").jsonPrimitive.content)
        assertEquals(uncertain, service.prepare(71005, "uncertain", WritingType.TRANSLATION, 3, "library-plan"))
        store.prepare(71005, "process-lost", WritingType.FREE, 3, "library-plan")
        assertEquals("RUNNING", service.prepare(71005, "process-lost", WritingType.FREE, 3, "library-plan").getValue("status").jsonPrimitive.content)
        assertEquals(1, calls)
    }

    @Test
    fun `B2 선택은 저장 후 재시작해도 재과금하지 않고 소유자별 변형을 소진한다`() = withDatabase { runner ->
        val snapshots = CuratedWritingStore(runner, manifest, clock)
        val store = CuratedWritingGenerationStore(runner, snapshots, manifest.releaseId, clock)
        val chosen = mutableListOf<String>()
        val service = CuratedWritingGenerationService(store, ModelExecutionPort { command ->
            assertEquals(ModelTier.LUNA, command.tier)
            assertEquals(512, command.maxOutputTokens)
            val input = Json.parseToJsonElement(command.messages.single().content).jsonObject
            val allowed = input.getValue("allowedCoreIds").jsonArray.map { it.jsonPrimitive.content }
            val id = allowed.first()
            chosen += id
            ModelExecutionResult(buildJsonObject { put("coreId", id) }, 20, 10, "openai", "gpt-5.6-luna")
        }, clock)

        val first = service.prepare(71020, "b2-first", WritingType.TRANSLATION, 2, "library-use")
        val restarted = CuratedWritingGenerationService(
            CuratedWritingGenerationStore(runner, snapshots, manifest.releaseId, clock),
            ModelExecutionPort { error("조회와 재시작은 모델을 호출하면 안 됩니다") }, clock)
        assertEquals(first, restarted.get(71020, "b2-first"))
        assertEquals(first, restarted.prepare(71020, "b2-first", WritingType.TRANSLATION, 2, "library-use"))
        val second = service.prepare(71020, "b2-second", WritingType.TRANSLATION, 2, "library-use")
        assertEquals(2, chosen.distinct().size)
        assertNotEquals(first.getValue("catalogId"), second.getValue("catalogId"))
        assertEquals("SUCCEEDED", second.getValue("status").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> {
            service.prepare(71020, "b2-third", WritingType.TRANSLATION, 2, "library-use")
        }.also { assertEquals("WRITING_CONTENT_NOT_AVAILABLE", it.message) }
        assertFailsWith<IllegalStateException> { restarted.get(71021, "b2-first") }
        val otherOwner = service.prepare(71021, "b2-other", WritingType.TRANSLATION, 2, "library-use")
        assertEquals("SUCCEEDED", otherOwner.getValue("status").jsonPrimitive.content)
        assertEquals(3, chosen.size)
        runner.read {
            assertEquals(3L, CuratedCatalogTable.selectAll().count())
            assertEquals(3L, CuratedGenerationTable.selectAll().count())
        }
    }

    @Test
    fun `다른 유형 준비 기록이 끼어도 이미 사용한 B2 코어를 다시 제공하지 않는다`() = withDatabase { runner ->
        val snapshots = CuratedWritingStore(runner, manifest, clock)
        val store = CuratedWritingGenerationStore(runner, snapshots, manifest.releaseId, clock)
        val chosen = mutableListOf<String>()
        val service = CuratedWritingGenerationService(store, ModelExecutionPort { command ->
            val input = Json.parseToJsonElement(command.messages.single().content).jsonObject
            val id = input.getValue("allowedCoreIds").jsonArray.first().jsonPrimitive.content
            chosen += id
            ModelExecutionResult(buildJsonObject { put("coreId", id) }, 20, 10, "openai", "gpt-5.6-luna")
        }, clock)
        service.prepare(71022, "b2-old", WritingType.TRANSLATION, 2, "home-task")
        repeat(13) { index ->
            val key = "unrelated-$index"
            store.prepare(71022, key, WritingType.FREE, 3, "library-plan")
            store.saveOutput(71022, key, result(WritingType.FREE))
        }
        service.prepare(71022, "b2-new", WritingType.TRANSLATION, 2, "home-task")
        assertEquals(2, chosen.distinct().size)
        val failure = assertFailsWith<IllegalArgumentException> {
            service.prepare(71022, "b2-exhausted", WritingType.TRANSLATION, 2, "home-task")
        }
        assertEquals("WRITING_CONTENT_NOT_AVAILABLE", failure.message)
        assertEquals(2, chosen.size)
    }

    private fun withDatabase(block: suspend (JdbcTransactionRunner) -> Unit) = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory -> runBlocking { block(JdbcTransactionRunner(factory.database, 4)) } }
    }
}
