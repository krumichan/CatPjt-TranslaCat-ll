package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthProfile
import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.table.GrowthProfilesTable
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingManifestCodec
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.*
import jp.co.translacat.languagelearning.shared.ai.*
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.SQLException
import kotlin.test.*

/** 실제 MySQL에 저장하지만 Provider만 합성한다. 현재 V13 경로의 단회 국소 복구를 검사한다. */
class CuratedWritingStoreIntegrationTestSemantic {
    private val clock = Clock.fixed(Instant.parse("2026-10-17T03:00:00Z"), ZoneOffset.UTC)
    private val date = LocalDate.parse("2026-10-17")

    @Test
    fun `등록된 의미 계약은 세 단계로 저장하고 재시작 조회와 중복 제출은 호출하지 않는다`() = exercise()

    @Test
    fun `부당 교정은 의미와 근거를 고정한 단회 국소 복구에서 제거한다`() = exercise(localRepair = true)

    @Test
    fun `위조 근거는 교정 단계 전에 실패로 저장하고 제출 재전송은 자동 재시도하지 않는다`() = exercise(invalidEvidence = true)

    @Test
    fun `타인 재시도는 DB 저장 실패의 검증 캐시를 지우지 않고 본인 무과금 복구를 보존한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory -> runBlocking {
            // 준비: 소유 catalog의 성공 저장만 실패시키고, 실제 검증 결과는 worker 메모리에 남긴다.
            val runner = JdbcTransactionRunner(factory.database, 4)
            val manifest = CuratedWritingSemanticTestModel.manifest()
            val snapshots = CuratedWritingStore(runner, manifest, clock)
            snapshots.importManifest()
            val user = 9415L
            listOf(user, user + 1).forEach { owner ->
                ExposedWritingSetUnitOfWork(runner, clock).write(owner) {
                    val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                    growth.saveProfile(GrowthProfile(userId = owner, state = "ACTIVE", baseLevelScore = 60.0,
                        createdAt = now, updatedAt = now))
                }
            }
            var calls = 0
            val model = ModelExecutionPort { command ->
                calls++
                ModelExecutionResult(CuratedWritingSemanticTestModel.response(command), 10, 5, "openai", "gpt-5-mini")
            }
            val worker = CuratedWritingFeedbackWorker(runner, snapshots, manifest.releaseId, model, clock)
            val plans = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId, clock = clock, feedback = worker)
            val request = CuratedWritingStore.Start(user, date, WritingType.TRANSLATION, "ko", "ja", false, true, 7)
            val started = plans.start(request, 1, false)
            val item = started.getValue("items").jsonArray.single().jsonObject
            val setId = LearningPublicId.decode(started.getValue("dailySetId").jsonPrimitive.content)
            val itemId = LearningPublicId.decode(item.getValue("itemId").jsonPrimitive.content)
            val revision = item.getValue("contentRevision").jsonPrimitive.content
            CuratedWritingLegacySemanticSeed.pending(runner, snapshots, request, itemId,
                CuratedWritingSemanticTestModel.answer, revision, clock)
            db.connect().use { connection -> connection.createStatement().use { statement ->
                statement.execute("""
                    CREATE TRIGGER qa_reject_feedback_save BEFORE UPDATE ON language_learning_curated_writing_feedback
                    FOR EACH ROW BEGIN
                        IF NEW.status = 'SUCCEEDED' THEN
                            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'qa_owned_feedback_save_failure';
                        END IF;
                    END
                """.trimIndent())
            } }
            try {
                // 실행: 세 모델 단계는 완료되지만 DB의 마지막 성공 UPDATE만 명시적으로 거절된다.
                val saveFailure = assertFails {
                    plans.submit(request, itemId, CuratedWritingSemanticTestModel.answer, revision)
                }
                assertTrue(generateSequence(saveFailure as Throwable?) { it.cause }.filterIsInstance<SQLException>()
                    .any { it.sqlState == "45000" }, saveFailure.javaClass.name)
                assertEquals(3, calls)
                val answerId = runner.read { CuratedWritingFeedbackTable.selectAll().single()[CuratedWritingFeedbackTable.answerId] }
                suspend fun pendingState(): JsonObject {
                    runner.read {
                        val row = CuratedWritingFeedbackTable.selectAll().single()
                        assertEquals(user, row[CuratedWritingFeedbackTable.userId])
                        assertEquals("PROCESSING", row[CuratedWritingFeedbackTable.status])
                        assertNull(row[CuratedWritingFeedbackTable.resultJson])
                    }
                    return assertNotNull(plans.byId(user, setId, date, 7)).getValue("items").jsonArray.single().jsonObject
                        .getValue("attempts").jsonArray.single().jsonObject.getValue("feedback").jsonObject
                }
                val beforeIntrusion = pendingState()
                assertTrue(beforeIntrusion.getValue("canRetry").jsonPrimitive.boolean)

                // 검증: 같은 answer ID를 추측한 다른 owner는 캐시 삭제·저장·추가 모델 호출을 일으키지 않는다.
                val rejected = assertFails { plans.retryFeedback(request.copy(userId = user + 1), answerId) }
                assertEquals("WRITING_ANSWER_NOT_FOUND", rejected.message)
                assertEquals(beforeIntrusion, pendingState())
                assertEquals(3, calls)
                db.connect().use { connection -> connection.createStatement().use { it.execute("DROP TRIGGER qa_reject_feedback_save") } }

                // 검증: 본인은 보존한 검증 결과만 저장하고 공식 평가·재시도 횟수·과금을 늘리지 않는다.
                val recovered = plans.retryFeedback(request, answerId)
                val feedback = recovered.getValue("items").jsonArray.single().jsonObject.getValue("attempts")
                    .jsonArray.single().jsonObject.getValue("feedback").jsonObject
                assertEquals("SUCCEEDED", feedback.getValue("status").jsonPrimitive.content)
                assertEquals(3, calls)
                runner.read {
                    val row = CuratedWritingFeedbackTable.selectAll().single()
                    assertEquals(1, row[CuratedWritingFeedbackTable.attemptCount])
                    assertEquals(30, row[CuratedWritingFeedbackTable.inputTokens])
                    assertEquals(15, row[CuratedWritingFeedbackTable.outputTokens])
                    assertEquals(0L, WritingEvaluationsTable.selectAll().count())
                    assertTrue(GrowthProfilesTable.selectAll().all { it[GrowthProfilesTable.evaluationCount] == 0 })
                }
            } finally {
                // 실패한 경우에도 이 테스트가 만든 trigger만 제거한다. catalog 정리는 기존 scratch 소유 규칙을 따른다.
                db.connect().use { connection -> connection.createStatement().use { it.execute("DROP TRIGGER IF EXISTS qa_reject_feedback_save") } }
            }
        } }
    }

    private fun exercise(localRepair: Boolean = false, invalidEvidence: Boolean = false) = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory -> runBlocking {
            // 준비: 기존 공개 과제의 DRAFT 하나와 격리 owner를 사용한다. 채점·공식 평가를 생성하지 않는다.
            CuratedWritingSemanticTestModel.verifyQaCasesIfRequested()
            val runner = JdbcTransactionRunner(factory.database, 4)
            val manifest = CuratedWritingSemanticTestModel.manifest()
            val snapshots = CuratedWritingStore(runner, manifest, clock)
            snapshots.importManifest()
            val user = 9413L
            ExposedWritingSetUnitOfWork(runner, clock).write(user) {
                val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                growth.saveProfile(GrowthProfile(userId = user, state = "ACTIVE", baseLevelScore = 60.0,
                    createdAt = now, updatedAt = now))
            }
            val commands = mutableListOf<ModelExecutionCommand>()
            var frozenEvidence: JsonElement? = null
            val model = ModelExecutionPort { command ->
                commands += command
                val input = Json.parseToJsonElement(command.messages.single().content).jsonObject
                // 명시한 로컬 QA에서만 비밀이 없는 합성 첫 명령을 보존해 외부 비용 gate와 직렬화를 대조한다.
                System.getenv("LL_QA_SEMANTIC_SPECIMEN")?.takeIf { command.schemaName == "writing_observed_claims" }?.let {
                    val path = Path.of(it)
                    if (!Files.exists(path)) Files.writeString(path, command.messages.single().content, StandardOpenOption.CREATE_NEW)
                }
                assertEquals(ModelTier.MINI, command.tier)
                assertEquals(4096, command.maxOutputTokens)
                if (command.schemaName == "writing_local_corrections") frozenEvidence = input.getValue("frozenEvidence")
                if (command.schemaName == "writing_local_correction_repair") {
                    assertEquals(frozenEvidence, input.getValue("frozenEvidence"))
                    assertTrue(input.getValue("invalidOriginalRows").jsonArray.isNotEmpty())
                    assertTrue(input.getValue("frozenNecessaryCorrections").jsonArray.isEmpty())
                }
                ModelExecutionResult(CuratedWritingSemanticTestModel.response(command, invalidEvidence, localRepair),
                    10, 5, "openai", "gpt-5-mini-2025-08-07")
            }
            val worker = CuratedWritingFeedbackWorker(runner, snapshots, manifest.releaseId, model, clock)
            val plans = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId, clock = clock, feedback = worker)
            val request = CuratedWritingStore.Start(user, date, WritingType.TRANSLATION, "ko", "ja", false, true, 7)
            val item = plans.start(request, 1, false).getValue("items").jsonArray.single().jsonObject
            val itemId = LearningPublicId.decode(item.getValue("itemId").jsonPrimitive.content)
            val revision = item.getValue("contentRevision").jsonPrimitive.content
            CuratedWritingLegacySemanticSeed.pending(runner, snapshots, request, itemId,
                CuratedWritingSemanticTestModel.answer, revision, clock)

            // 실행: 실제 claim·모델 단계·결과 저장을 거친 후 새 worker로 조회한다.
            val saved = plans.submit(request, itemId, CuratedWritingSemanticTestModel.answer, revision)
            val state = saved.getValue("items").jsonArray.single().jsonObject.getValue("attempts").jsonArray.single()
                .jsonObject.getValue("feedback").jsonObject
            val expectedCalls = if (invalidEvidence) 2 else if (localRepair) 4 else 3
            assertEquals(expectedCalls, commands.size, state.toString())
            assertEquals(if (invalidEvidence) "FAILED" else "SUCCEEDED", state.getValue("status").jsonPrimitive.content)
            assertEquals(saved, plans.submit(request, itemId, CuratedWritingSemanticTestModel.answer, revision))
            val restarted = CuratedWritingFeedbackWorker(runner, snapshots, manifest.releaseId,
                ModelExecutionPort { error("GET must not invoke AI") }, clock)
            val restartedPlans = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId, clock = clock, feedback = restarted)
            val setId = LearningPublicId.decode(saved.getValue("dailySetId").jsonPrimitive.content)
            assertEquals(saved, restartedPlans.byId(user, setId, date, 7))
            assertEquals(expectedCalls, commands.size)

            // 검증: V13 identity·단계 한도·저장 해시와 공식 점수 분리를 보존한다.
            runner.read {
                val row = CuratedWritingFeedbackTable.selectAll().single()
                assertEquals("curated-writing-feedback-v13", row[CuratedWritingFeedbackTable.policyVersion])
                assertEquals(1, row[CuratedWritingFeedbackTable.attemptCount])
                if (!invalidEvidence) {
                    val result = state.getValue("result").jsonObject
                    assertEquals("curated-writing-feedback-v13", result.getValue("policyVersion").jsonPrimitive.content)
                    assertEquals(64, result.getValue("meaningContractHash").jsonPrimitive.content.length)
                    val contractHash = result.getValue("meaningContractHash").jsonPrimitive.content
                    assertEquals(CuratedWritingManifestCodec.sha256("$user|${row[CuratedWritingFeedbackTable.answerId]}|" +
                        "${row[CuratedWritingFeedbackTable.answerHash]}|${row[CuratedWritingFeedbackTable.sourceHash]}|" +
                        "curated-writing-feedback-v13|$contractHash"), row[CuratedWritingFeedbackTable.requestHash])
                    assertTrue(result.getValue("necessaryCorrections").jsonArray.isEmpty())
                    assertEquals(expectedCalls * 10, row[CuratedWritingFeedbackTable.inputTokens])
                } else assertNull(row[CuratedWritingFeedbackTable.resultJson])
                assertEquals(0L, WritingEvaluationsTable.selectAll().count())
                assertEquals(0, GrowthProfilesTable.selectAll().single()[GrowthProfilesTable.evaluationCount])
            }
        } }
    }
}
