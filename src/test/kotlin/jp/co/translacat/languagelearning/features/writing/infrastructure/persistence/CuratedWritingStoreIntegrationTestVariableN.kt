package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthProfile
import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.table.GrowthProfilesTable
import jp.co.translacat.languagelearning.features.writing.application.WritingGenerationState
import jp.co.translacat.languagelearning.features.writing.domain.model.NewWritingSet
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.*
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.*
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.*
import kotlin.test.*

/** 합성 재고를 실제 scratch MySQL에서 검증한다. Provider·사람 승인·공식 평가와 무관하다. */
class CuratedWritingStoreIntegrationTestVariableN {
    private val date = LocalDate.parse("2026-10-04")
    private val instant = Instant.parse("2026-10-04T03:00:00Z")
    private val clock = Clock.fixed(instant, ZoneOffset.UTC)
    private fun request(user: Long, type: WritingType = WritingType.TRANSLATION) =
        CuratedWritingStore.Start(user, date, type, "ko", "ja", false, true, 7)
    private fun JsonObject.id() = LearningPublicId.decode(getValue("dailySetId").jsonPrimitive.content)
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.items() = getValue("items").jsonArray.map { it.jsonObject }

    @Test
    fun `세 유형과 가변 목표 전체를 실제 DB에 저장하고 읽기는 쓰기를 만들지 않는다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory -> runBlocking {
            // 준비: 각 band에 충분한 합성 재고. 각 계획은 별도 owner로 노출 이력과 분리한다.
            val runner = JdbcTransactionRunner(factory.database, 4)
            val manifest = CuratedWritingPlanFixtures.manifest(100)
            val snapshots = CuratedWritingStore(runner, manifest, clock)
            val plans = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId, clock = clock)
            snapshots.importManifest()
            var user = 1000L
            for (type in WritingType.entries) for (target in listOf(1, 2, 3, 5, 10, 20, 30, 50, 100)) {
                seed(runner, ++user)
                val req = request(user, type)

                // 실행: preview와 중복 시작은 저장 snapshot을 변경하지 않는다.
                val preview = plans.preview(req, target)
                assertEquals(target, preview.number("acquiredItemCount"))
                val saved = plans.start(req, target, false)
                assertEquals(saved, plans.start(req, target, false))
                assertEquals(saved, plans.byId(user, saved.id(), date, 7))
                assertEquals(saved, plans.byDate(user, date, type, date, 7))

                // 검증: 정확한 수, 별도 목표·피드백수, 중복0, 제출 전 비공개.
                assertEquals(target, saved.number("targetItemCount"))
                assertEquals(manifest.releaseId, saved.getValue("releaseId").jsonPrimitive.content)
                assertEquals(target, saved.number("acquiredItemCount"))
                assertEquals(0, saved.number("submittedItemCount"))
                assertEquals(0, saved.number("feedbackCompletedItemCount"))
                assertEquals(target, saved.items().map { it.getValue("catalogId") }.distinct().size)
                assertTrue(saved.items().all { "reference" !in it && it.getValue("attempts").jsonArray.isEmpty() })
                assertEquals("READY", saved.getValue("status").jsonPrimitive.content)
                assertNull(plans.byId(user + 10000, saved.id(), date, 7))
            }
            runner.read {
                assertEquals(27L, CuratedSetsTable.selectAll().count())
                assertEquals(0L, CuratedAnswersTable.selectAll().count())
                assertEquals(0L, WritingEvaluationsTable.selectAll().count())
                assertTrue(GrowthProfilesTable.selectAll().all { it[GrowthProfilesTable.evaluationCount] == 0 })
            }
        } }
    }

    @Test
    fun `27개 확보 후 재시작 보충은 세개만 채우고 기존 답안과 revision을 보존한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory -> runBlocking {
            // 준비: B3 normal만 세개 부족한 27개 재고와 명시적 부분 시작.
            val runner = JdbcTransactionRunner(factory.database, 4)
            val complete = CuratedWritingPlanFixtures.manifest(18)
            val partial = complete.copy(items = complete.items.filter { it.band != 3 || it.id.substringAfterLast('-').toInt() <= 15 })
            val oldSnapshots = CuratedWritingStore(runner, partial, clock)
            val old = CuratedWritingPlanStore(runner, oldSnapshots, partial.releaseId, clock = clock)
            oldSnapshots.importManifest()
            seed(runner, 2001)
            val req = request(2001)
            assertEquals(27, old.preview(req, 30).number("acquiredItemCount"))
            assertFailsWith<IllegalArgumentException> { old.start(req, 30, false) }
            runner.read { assertEquals(0L, CuratedSetsTable.selectAll().count()) }
            val initial = old.start(req, 30, true)
            assertEquals(27, initial.number("acquiredItemCount"))
            assertEquals("PARTIAL", initial.getValue("status").jsonPrimitive.content)

            // 실행: 확보된 모든 답안을 저장해도 목표30 미달이면 완료로 바꾸지 않는다.
            var submitted = initial
            initial.items().forEach { item ->
                submitted = old.submit(req, LearningPublicId.decode(item.getValue("itemId").jsonPrimitive.content),
                    "合成回答 ${item.getValue("order")}", item.getValue("contentRevision").jsonPrimitive.content)
            }
            assertEquals("PARTIAL", submitted.getValue("status").jsonPrimitive.content)
            assertEquals(27, submitted.number("submittedItemCount"))
            val beforeItems = submitted.items().associateBy { it.getValue("itemId") }

            // 실행: 새 Store 인스턴스와 추가 재고로 missing3만 채운다. 동일 작업 전달은 멱등이다.
            val nextSnapshots = CuratedWritingStore(runner, complete, clock)
            nextSnapshots.importManifest()
            val restarted = CuratedWritingPlanStore(runner, nextSnapshots, complete.releaseId, clock = clock)
            val claim = restarted.claimSupply(req, initial.id(), 1)
            val restored = restarted.completeSupply(req, claim)
            assertEquals(restored, restarted.completeSupply(req, claim))

            // 검증: 기존 snapshot·답안·revision은 전체 JSON으로 일치하며 새3개만 미제출이다.
            assertEquals(30, restored.number("acquiredItemCount"))
            assertEquals(27, restored.number("submittedItemCount"))
            restored.items().filter { it.getValue("itemId") in beforeItems }.forEach {
                assertEquals(beforeItems[it.getValue("itemId")], it)
            }
            assertEquals(3, restored.items().count { "reference" !in it })
            assertEquals("READY", restored.getValue("status").jsonPrimitive.content)
        } }
    }

    @Test
    fun `10에서30 확대는18답안 저장과 별도로 동작하고 언어 소유 정책 충돌을 막는다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory -> runBlocking {
            // 준비
            val runner = JdbcTransactionRunner(factory.database, 4)
            val manifest = CuratedWritingPlanFixtures.manifest(30)
            val snapshots = CuratedWritingStore(runner, manifest, clock)
            val plans = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId, clock = clock)
            snapshots.importManifest()
            listOf(3001L, 3002L, 3003L).forEach { seed(runner, it) }
            val req = request(3001, WritingType.FREE)
            val first = plans.start(req, 10, false)
            val oldItem = first.items().first()
            plans.submit(req, LearningPublicId.decode(oldItem.getValue("itemId").jsonPrimitive.content),
                "保存された回答", oldItem.getValue("contentRevision").jsonPrimitive.content)
            val before = checkNotNull(plans.byId(3001, first.id(), date, 7))

            // 실행: HTTP 문맥처럼 기본 type값을 보내도 저장된 유형으로만 보충한다.
            val expanded = plans.expand(req.copy(type = WritingType.TRANSLATION), first.id(), 30, 1)
            assertEquals(before.items(), expanded.items().take(10))
            assertEquals(2, expanded.number("planRevision"))
            assertEquals(30, expanded.number("acquiredItemCount"))
            assertEquals(expanded, plans.expand(req, first.id(), 30, 1))
            assertTrue(expanded.items().all { it.getValue("catalogId").jsonPrimitive.content.contains("free") })
            expanded.items().drop(1).take(17).forEach { item ->
                plans.submit(req, LearningPublicId.decode(item.getValue("itemId").jsonPrimitive.content),
                    "回答 ${item.getValue("order")}", item.getValue("contentRevision").jsonPrimitive.content)
            }

            // 검증: 개인화 피드백 비활성이 답안18개를 막지 않고 공식 평가를 만들지 않는다.
            val saved = checkNotNull(plans.byId(3001, first.id(), date, 7))
            assertEquals(18, saved.number("submittedItemCount"))
            assertEquals(0, saved.number("feedbackCompletedItemCount"))
            assertFailsWith<IllegalArgumentException> { plans.expand(req, first.id(), 10, 2) }
            assertFailsWith<IllegalArgumentException> { plans.expand(req, first.id(), 50, 1) }
            assertFailsWith<IllegalStateException> { plans.restore(req.copy(userId = 3002), first.id(), 2) }
            assertFailsWith<IllegalArgumentException> { plans.restore(req.copy(learningLanguage = "en"), first.id(), 2) }
            assertFailsWith<IllegalArgumentException> { snapshots.start(req) }
            assertFailsWith<IllegalArgumentException> {
                snapshots.submit(3001, LearningPublicId.decode(oldItem.getValue("itemId").jsonPrimitive.content),
                    "保存された回答", oldItem.getValue("contentRevision").jsonPrimitive.content, date, 7)
            }
            val fixedFive = snapshots.start(request(3002))
            assertFailsWith<IllegalArgumentException> { plans.start(request(3002), 10, false) }
            val invalidOrder = assertFailsWith<Exception> {
                runner.write {
                    CuratedItemsTable.update({ (CuratedItemsTable.setId eq fixedFive.id()) and
                        (CuratedItemsTable.itemOrder eq 5) }) { it[itemOrder] = 6 }
                }
            }
            assertTrue(invalidOrder.toString().contains("ck_ll_cwi_order"))
            assertEquals(fixedFive, snapshots.byId(3002, fixedFive.id(), date, 7))
            val legacy = WritingGenerationState(ExposedWritingSetUnitOfWork(runner, clock))
            assertFailsWith<IllegalArgumentException> { legacy.getOrCreate(NewWritingSet(3001, date, WritingType.FREE, "conflict", 1, "{}")) }
            legacy.getOrCreate(NewWritingSet(3003, date, WritingType.FREE, "legacy-first", 1, "{}"))
            assertFailsWith<IllegalArgumentException> { plans.start(request(3003, WritingType.FREE), 10, false) }
            runner.read { assertEquals(0L, WritingEvaluationsTable.selectAll().count()) }
        } }
    }

    @Test
    fun `lease 만료 취소 확대 후의 늦은 보충은 저장하지 않으며 재획득으로 복구한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory -> runBlocking {
            // 준비: 부족 세트를 만들고 두 독립 worker 인스턴스를 같은 DB에 연결한다.
            val runner = JdbcTransactionRunner(factory.database, 4)
            val manifest = CuratedWritingPlanFixtures.manifest(2)
            val snapshots = CuratedWritingStore(runner, manifest, clock)
            val plans = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId, clock = clock)
            snapshots.importManifest()
            seed(runner, 4001)
            val req = request(4001)
            val saved = plans.start(req, 30, true)
            val expiredClaim = plans.claimSupply(req, saved.id(), 1)
            assertFailsWith<IllegalArgumentException> { plans.claimSupply(req, saved.id(), 1) }
            val later = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId,
                clock = Clock.fixed(instant.plusSeconds(61), ZoneOffset.UTC))

            // 실행·검증: 만료 토큰을 거부하고 새 lease만 소유권을 얻는다.
            assertFailsWith<IllegalArgumentException> { later.completeSupply(req, expiredClaim) }
            val fresh = later.claimSupply(req, saved.id(), 1)
            assertFailsWith<IllegalArgumentException> { later.completeSupply(req, expiredClaim) }
            later.stopSupply(req, fresh, "CANCELLED")
            assertFailsWith<IllegalArgumentException> { later.completeSupply(req, fresh) }
            val cancelled = checkNotNull(later.byId(4001, saved.id(), date, 7))
            assertEquals(saved.items(), cancelled.items())
            assertEquals(30, cancelled.number("targetItemCount"))

            // 실행·검증: 확대 revision으로 구 작업을 차단한다. 새 작업 복구는 중복 item을 만들지 않는다.
            val beforeExpansion = later.claimSupply(req, saved.id(), 1)
            val expanded = later.expand(req, saved.id(), 50, 1)
            assertFailsWith<IllegalArgumentException> { later.completeSupply(req, beforeExpansion) }
            val restored = later.restore(req, saved.id(), 2)
            assertEquals(expanded.items(), restored.items())
            assertEquals(50, restored.number("targetItemCount"))
            assertEquals(restored.items().size, restored.items().map { it.getValue("catalogId") }.distinct().size)
        } }
    }

    @Test
    fun `빈 계획을 확대로 처음 확보하면 기존 준비 projection만 반영하고 평가값을 만들지 않는다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory -> runBlocking {
            // 준비: 기존 CALIBRATING 정책에서 문항이 하나도 확보되지 않은 계획은 시작일을 건드리지 않는다.
            val runner = JdbcTransactionRunner(factory.database, 4)
            val empty = CuratedWritingManifest("synthetic-variable-n", emptyList())
            val snapshots = CuratedWritingStore(runner, empty, clock)
            val plans = CuratedWritingPlanStore(runner, snapshots, empty.releaseId, clock = clock)
            val work = ExposedWritingSetUnitOfWork(runner, clock)
            work.write(8001) {
                val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                growth.saveProfile(GrowthProfile(userId = 8001, state = "CALIBRATING", baseLevelScore = 60.0,
                    createdAt = now, updatedAt = now))
            }
            val req = request(8001)
            val blocked = plans.start(req, 5, true)
            val before = work.read { checkNotNull(growth.profile(8001)) }
            assertEquals(0, blocked.number("acquiredItemCount"))
            assertNull(before.calibrationStartedDate)
            val stock = CuratedWritingPlanFixtures.manifest(20)
            val stocked = CuratedWritingStore(runner, stock, clock)
            stocked.importManifest()
            val restarted = CuratedWritingPlanStore(runner, stocked, stock.releaseId, clock = clock)

            // 실행: 확대가 최초의 실제 확보 경로가 된다.
            val expanded = restarted.expand(req, blocked.id(), 10, 1)
            val after = work.read { checkNotNull(growth.profile(8001)) }

            // 검증: 기존 준비 정책의 시작일만 바뀌며 점수·평가 횟수·기본 단계는 그대로다.
            assertEquals(10, expanded.number("acquiredItemCount"))
            assertEquals(date, after.calibrationStartedDate)
            assertEquals(before.copy(calibrationStartedDate = date), after)
            assertEquals(0, after.evaluationCount)
            assertNull(after.meaningScore)
            assertNull(after.grammarScore)
            assertNull(after.vocabularyScore)
            assertNull(after.naturalnessScore)
            assertNull(after.expressionScore)
            runner.read { assertEquals(0L, WritingEvaluationsTable.selectAll().count()) }
        } }
    }

    private suspend fun seed(runner: JdbcTransactionRunner, userId: Long) {
        ExposedWritingSetUnitOfWork(runner, clock).write(userId) {
            val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
            growth.saveProfile(GrowthProfile(userId = userId, state = "ACTIVE", baseLevelScore = 60.0,
                createdAt = now, updatedAt = now))
        }
    }

    @Test
    fun `앞 슬롯 재고가 없어도 뒤의 공급 가능한 셀을 배치 크기만큼 복구한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory -> runBlocking {
            // 준비: 목표100과 재고0을 보존한 다음 뒤쪽 CHALLENGE 재고만 추가한다.
            val runner = JdbcTransactionRunner(factory.database, 4)
            val empty = CuratedWritingManifest("synthetic-variable-n", emptyList())
            val original = CuratedWritingStore(runner, empty, clock)
            val limits = CuratedWritingPlanLimits(workerBatchSize = 5)
            val plans = CuratedWritingPlanStore(runner, original, empty.releaseId, limits, clock)
            seed(runner, 6001)
            val req = request(6001)
            val initial = plans.start(req, 100, true)
            assertEquals("BLOCKED", initial.getValue("status").jsonPrimitive.content)
            val stock = empty.copy(items = (1..20).map { CuratedWritingPlanFixtures.item(WritingType.TRANSLATION, 4, it) })
            val next = CuratedWritingStore(runner, stock, clock)
            next.importManifest()
            val restarted = CuratedWritingPlanStore(runner, next, stock.releaseId, limits, clock)

            // 실행
            val first = restarted.restore(req, initial.id(), 1)
            val second = restarted.restore(req, initial.id(), 1)

            // 검증: 재고 없는 NORMAL은 남겨도 뒤의 공급 가능 항목은 진행된다.
            assertEquals(5, first.number("acquiredItemCount"))
            assertEquals(10, second.number("acquiredItemCount"))
            assertTrue(second.items().all { it.getValue("difficulty").jsonPrimitive.content == "CHALLENGE" })
            assertTrue(second.items().containsAll(first.items()))
            assertEquals(90, second.number("remainingItemCount"))
        } }
    }

    @Test
    fun `다른 ID의 같은 의미 노출은 새 문항으로 반복하지 않고 명시적 재학습만 허용한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory -> runBlocking {
            // 준비: 이미 노출된 의미 키를 새 catalog ID로 가져온다.
            val runner = JdbcTransactionRunner(factory.database, 4)
            val item = CuratedWritingPlanFixtures.item(WritingType.TRANSLATION, 3, 1)
            val manifest = CuratedWritingManifest(item.releaseId, listOf(item))
            val original = CuratedWritingStore(runner, manifest, clock)
            original.importManifest()
            seed(runner, 7001)
            val req = request(7001)
            CuratedWritingPlanStore(runner, original, manifest.releaseId, clock = clock).start(req, 1, false)
            val alias = item.copy(id = item.id + "-alias", contentHash = CuratedWritingManifestCodec.sha256("alias"))
            val changed = manifest.copy(items = listOf(alias))
            val snapshots = CuratedWritingStore(runner, changed, clock)
            snapshots.importManifest()
            val plans = CuratedWritingPlanStore(runner, snapshots, changed.releaseId, clock = clock)
            val tomorrow = req.copy(date = date.plusDays(1))

            // 실행·검증: 다른 ID라도 같은 의미는 새 재고로 계산하지 않는다. 공개 승인도 자동 부여하지 않는다.
            assertEquals(0, plans.preview(tomorrow, 1).number("acquiredItemCount"))
            assertEquals(1, plans.preview(tomorrow.copy(rePractice = true), 1).number("acquiredItemCount"))
            assertEquals(0, plans.preview(tomorrow.copy(rePractice = true, qaOnly = false), 1).number("acquiredItemCount"))
            val repeated = plans.start(tomorrow.copy(rePractice = true), 1, false)
            assertTrue(repeated.getValue("rePractice").jsonPrimitive.boolean)
        } }
    }
}
