package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.application.QueryGrowth
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthProfile
import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.ExposedGrowthUnitOfWork
import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.table.*
import jp.co.translacat.languagelearning.features.learner.domain.exception.LearnerUnavailableException
import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.table.LearnersTable
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingPlanFixtures
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.*
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import jp.co.translacat.languagelearning.shared.ai.*
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.*
import kotlin.test.*

/** 신규 소유 scratch DB만 사용하며 모델·기존 QA 답안·공식 점수를 변경하지 않는다. */
class CuratedWritingStoreIntegrationTestActivity {
    private val clock = Clock.fixed(Instant.parse("2026-10-10T15:05:00Z"), ZoneOffset.UTC)
    private val date = LocalDate.parse("2026-10-11")
    private fun request(user: Long) = CuratedWritingStore.Start(user, date, WritingType.FREE, "ko", "ja", false, true, 7)
    private fun JsonObject.id() = LearningPublicId.decode(getValue("dailySetId").jsonPrimitive.content)
    private fun JsonObject.items() = getValue("items").jsonArray.map { it.jsonObject }
    private fun JsonObject.activity() = getValue("activity").jsonObject
    private fun JsonObject.metadata() = activity().getValue("metadata").jsonObject
    private suspend fun seed(runner: JdbcTransactionRunner, user: Long) {
        ExposedWritingSetUnitOfWork(runner, clock).write(user) {
            val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
            growth.saveProfile(GrowthProfile(userId = user, state = "ACTIVE", baseLevelScore = 60.0,
                createdAt = now, updatedAt = now))
        }
    }
    private suspend fun submit(plans: CuratedWritingPlanStore, req: CuratedWritingStore.Start, item: JsonObject) =
        plans.submit(req, LearningPublicId.decode(item.getValue("itemId").jsonPrimitive.content),
            "学習回答 ${item.getValue("order")}", item.getValue("contentRevision").jsonPrimitive.content)

    @Test
    fun `완료한 10문항 계획의30확대와18제출은 같은 비점수 활동으로 재시작 후에도 유지된다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory -> runBlocking {
            // 준비: 날짜 경계에서 학습일은 UTC 날짜와 다른 저장 snapshot이다.
            val runner = JdbcTransactionRunner(factory.database, 4)
            val manifest = CuratedWritingPlanFixtures.manifest(30)
            val snapshots = CuratedWritingStore(runner, manifest, clock)
            val plans = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId, clock = clock)
            snapshots.importManifest()
            seed(runner, 9501)
            val req = request(9501)
            val initial = plans.start(req, 10, false)
            assertEquals(JsonNull, initial["activity"])
            val profileBefore = runner.read { GrowthProfilesTable.selectAll().single().let {
                it[GrowthProfilesTable.updatedAt] to it[GrowthProfilesTable.evaluationCount]
            } }

            // 실행: 잘못 쓴 문장도 정상 접수된 학습 사실이며, 최초 제출부터 한 활동의 진행을 갱신한다.
            val first = submit(plans, req, initial.items().first())
            assertEquals("IN_PROGRESS", first.activity().getValue("status").jsonPrimitive.content)
            var complete = first
            initial.items().drop(1).forEach { complete = submit(plans, req, it) }
            assertEquals("COMPLETED", complete.activity().getValue("status").jsonPrimitive.content)
            val activityId = runner.read { GrowthActivitiesTable.selectAll().single()[GrowthActivitiesTable.id] }
            val answersBefore = runner.read { CuratedAnswersTable.selectAll().map { it[CuratedAnswersTable.id] } }
            val expanded = plans.expand(req, initial.id(), 30, 1)
            assertEquals("IN_PROGRESS", expanded.activity().getValue("status").jsonPrimitive.content)
            expanded.items().drop(10).take(8).forEach { submit(plans, req, it) }
            val restarted = CuratedWritingPlanStore(runner, CuratedWritingStore(runner, manifest, clock), manifest.releaseId, clock = clock)
            val saved = checkNotNull(restarted.byId(9501, initial.id(), date, 7))
            val revision = runner.read { GrowthActivityRevisionsTable.selectAll().single()[GrowthActivityRevisionsTable.revision] }
            assertEquals(saved, submit(restarted, req, saved.items().first()))
            assertEquals(saved, restarted.expand(req, initial.id(), 30, 1))

            // 검증: 18/30은 완료가 아니고 score·metric·profile·학습시간을 만들지 않는다.
            assertEquals(18, saved.metadata().getValue("submittedItemCount").jsonPrimitive.int)
            assertEquals(30, saved.metadata().getValue("targetItemCount").jsonPrimitive.int)
            assertEquals("ko", saved.metadata().getValue("originLanguage").jsonPrimitive.content)
            assertEquals(date.toString(), saved.activity().getValue("learningDate").jsonPrimitive.content)
            assertEquals(saved.activity(), restarted.byId(9501, initial.id(), date.plusDays(1), 7)?.activity())
            val page = QueryGrowth(ExposedGrowthUnitOfWork(runner, clock)).activities(9501, "WRITING", date, date, 0)
            assertEquals(1, page.activities.size)
            assertEquals("IN_PROGRESS", page.activities.single().first.status)
            runner.read {
                val activity = GrowthActivitiesTable.selectAll().single()
                assertEquals(activityId, activity[GrowthActivitiesTable.id])
                assertEquals(0L, activity[GrowthActivitiesTable.durationSeconds])
                assertNull(activity[GrowthActivitiesTable.completedAt])
                assertNull(activity[GrowthActivitiesTable.overallScore])
                assertEquals(revision, GrowthActivityRevisionsTable.selectAll().single()[GrowthActivityRevisionsTable.revision])
                assertTrue(CuratedAnswersTable.selectAll().map { it[CuratedAnswersTable.id] }.containsAll(answersBefore))
                assertEquals(18L, CuratedAnswersTable.selectAll().count())
                assertEquals(0L, GrowthMetricsTable.selectAll().count())
                assertEquals(0L, GrowthEvidenceTable.selectAll().count())
                assertEquals(0L, WritingEvaluationsTable.selectAll().count())
                assertEquals(profileBefore, GrowthProfilesTable.selectAll().single().let {
                    it[GrowthProfilesTable.updatedAt] to it[GrowthProfilesTable.evaluationCount]
                })
            }
            assertFailsWith<IllegalStateException> { submit(restarted, req.copy(userId = 9599), saved.items().first()) }
            assertFailsWith<IllegalArgumentException> { submit(restarted, req.copy(learningLanguage = "en"), saved.items().first()) }
        } }
    }

    @Test
    fun `계획 삭제는 소유 활동과 자식만 제거하고 늦은 제출과보충이 되살리지 못한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory -> runBlocking {
            // 준비: 두 소유자의 새 계획. 삭제할 계획에 늦은 공급 토큰도 발급한다.
            val runner = JdbcTransactionRunner(factory.database, 4)
            val manifest = CuratedWritingPlanFixtures.manifest(3)
            val snapshots = CuratedWritingStore(runner, manifest, clock)
            val plans = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId, clock = clock)
            snapshots.importManifest()
            listOf(9502L, 9503L).forEach { seed(runner, it) }
            val req = request(9502)
            val saved = plans.start(req, 1, false)
            val other = plans.start(request(9503), 1, false)
            submit(plans, req, saved.items().single())
            submit(plans, request(9503), other.items().single())
            val claim = plans.claimSupply(req, saved.id(), 1)
            assertFailsWith<IllegalStateException> { plans.delete(request(9503), saved.id(), 1) }
            assertFailsWith<IllegalArgumentException> { plans.delete(req, saved.id(), 2) }

            // 실행: 제품의 owner/revision 검사와 FK 순서를 거치는 삭제 경로를 사용한다.
            assertTrue(plans.delete(req, saved.id(), 1).getValue("deleted").jsonPrimitive.boolean)

            // 검증: 삭제된 활동은 조회에서 사라지고 기존 타인 계획·카탈로그는 보존된다.
            assertNull(plans.byId(9502, saved.id(), date, 7))
            assertFailsWith<IllegalStateException> { submit(plans, req, saved.items().single()) }
            assertFailsWith<IllegalStateException> { plans.completeSupply(req, claim) }
            assertTrue(QueryGrowth(ExposedGrowthUnitOfWork(runner, clock)).activities(9502, "WRITING", date, date, 0).activities.isEmpty())
            runner.read {
                assertEquals(1L, CuratedSetsTable.selectAll().count())
                assertEquals(1L, CuratedAnswersTable.selectAll().count())
                assertEquals(1L, GrowthActivitiesTable.selectAll().count())
                assertEquals(9503L, GrowthActivitiesTable.selectAll().single()[GrowthActivitiesTable.userId])
                assertEquals(manifest.items.size.toLong(), CuratedCatalogTable.selectAll().count())
            }
        } }
    }

    @Test
    fun `삭제중 계정의 활동과계획은 제외하고 접수나늦은공급으로 복원하지 않는다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory -> runBlocking {
            // 준비: 기존 LL 학습자 tombstone 규칙을 새 활동에도 적용한다.
            val runner = JdbcTransactionRunner(factory.database, 4)
            val manifest = CuratedWritingPlanFixtures.manifest(3)
            val snapshots = CuratedWritingStore(runner, manifest, clock)
            val plans = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId, clock = clock)
            snapshots.importManifest()
            seed(runner, 9504)
            val req = request(9504)
            val saved = plans.start(req, 1, false)
            submit(plans, req, saved.items().single())
            val claim = plans.claimSupply(req, saved.id(), 1)

            // 실행: 실제 계정 transport 대신 기존 learner 상태를 owned scratch DB에 전환한다.
            runner.write { LearnersTable.update({ LearnersTable.userId eq 9504L }) { it[status] = "DELETION_PENDING" } }

            // 검증: 공개 조회·새 접수·공급은 모두 기존 tombstone을 존중한다. 물리 삭제 완료는 외부 계정 작업이다.
            assertFailsWith<LearnerUnavailableException> { plans.byId(9504, saved.id(), date, 7) }
            assertFailsWith<LearnerUnavailableException> { submit(plans, req, saved.items().single()) }
            assertFailsWith<LearnerUnavailableException> { plans.completeSupply(req, claim) }
            assertFailsWith<LearnerUnavailableException> {
                QueryGrowth(ExposedGrowthUnitOfWork(runner, clock)).activities(9504, "WRITING", date, date, 0)
            }
            runner.read {
                assertEquals("DELETION_PENDING", LearnersTable.selectAll().single()[LearnersTable.status])
                assertEquals(1L, GrowthActivitiesTable.selectAll().count())
                assertEquals(1L, CuratedAnswersTable.selectAll().count())
            }
        } }
    }

    @Test
    fun `모델 응답전 계획을삭제하면 늦은 피드백과 재시도가 활동을되살리지 않는다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory -> runBlocking {
            // 준비: 모델 실행 중 다른 사용자 요청처럼 같은 계획을 삭제할 수 있다.
            val runner = JdbcTransactionRunner(factory.database, 4)
            val manifest = CuratedWritingPlanFixtures.manifest(3)
            val snapshots = CuratedWritingStore(runner, manifest, clock)
            snapshots.importManifest()
            seed(runner, 9505)
            val req = request(9505)
            lateinit var plans: CuratedWritingPlanStore
            var setId = 0L
            var answerId = 0L
            var calls = 0
            val model = ModelExecutionPort { command ->
                calls++
                val input = Json.parseToJsonElement(command.messages.single().content).jsonObject
                if (calls == 1) {
                    answerId = runner.read { CuratedAnswersTable.selectAll().single()[CuratedAnswersTable.id] }
                    plans.delete(req, setId, 1)
                }
                ModelExecutionResult(CuratedWritingFeedbackTestPayload.v12Response(command),
                    100, 50, "openai", "gpt-5-mini")
            }
            val worker = CuratedWritingFeedbackWorker(runner, snapshots, manifest.releaseId, model, clock)
            plans = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId, clock = clock, feedback = worker)
            val saved = plans.start(req, 1, false)
            setId = saved.id()

            // 실행: 외부 응답이 돌아오기 전에 삭제가 커밋된 경우 저장/재시도는 없는 답안을 복원하지 않는다.
            assertFailsWith<IllegalStateException> { submit(plans, req, saved.items().single()) }
            assertFailsWith<IllegalStateException> { worker.retry(req, answerId) }

            // 검증: 이미 시작한 독립 검사 이후에도 삭제된 답안·활동을 복원하지 않는다.
            assertEquals(2, calls)
            runner.read {
                assertEquals(0L, CuratedSetsTable.selectAll().count())
                assertEquals(0L, CuratedAnswersTable.selectAll().count())
                assertEquals(0L, CuratedWritingFeedbackTable.selectAll().count())
                assertEquals(0L, GrowthActivitiesTable.selectAll().count())
            }
        } }
    }
}
