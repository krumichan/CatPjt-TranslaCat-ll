package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthProfile
import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.table.GrowthProfilesTable
import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.table.GrowthActivitiesTable
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
import java.sql.SQLException
import java.time.*
import kotlin.test.*

/** 실제 소유 scratch MySQL로 검사하고 AI 단계만 합성한다. 실모델 품질 판정이 아니다. */
class CuratedWritingStoreIntegrationTestServerRepair {
    @Test
    fun `새 답안 V14 핵심과 선택 결과는 저장 재시작 중복 owner revision 경계를 보존한다`() {
        CuratedWritingServerRepairQaExport.verifyIfRequested()
        val model = CuratedWritingSemanticV14TestModel(optionalMode = CuratedWritingSemanticV14TestModel.OptionalMode.VALID)
        scenario(model, answer = CuratedWritingSemanticTestModel.answer.replace("メール", "電子メール")) { scope ->
            // 실행: 숫자 표기만 바꾸는 무익한 대안 대신, 정상 문장의 의미를 보존하는 간결한 표현을 검사한다.
            val saved = scope.submit()
            val result = saved.feedback().getValue("result").jsonObject

            // 검증: 실제 DB 저장·무호출 조회/재시작·공식 점수 분리를 함께 확인한다.
            assertEquals(4, model.commands.size)
            assertTrue(model.commands.none { it.schemaName == "writing_server_repairs" })
            assertEquals("SUCCEEDED", result.string("optionalSuggestionsStatus"))
            assertEquals(1, result.getValue("optionalAlternatives").jsonArray.size)
            assertEquals("curated-writing-feedback-v14", result.string("policyVersion"))
            assertEquals("server-repair-plan-v1", result.string("repairPlanVersion"))
            assertEquals(64, result.string("repairPlanHash").length)
            assertEquals(saved, scope.submit())
            val restarted = CuratedWritingFeedbackWorker(scope.runner, scope.snapshots, scope.releaseId,
                ModelExecutionPort { error("Saved V14 GET must not call AI") }, clock)
            val plans = CuratedWritingPlanStore(scope.runner, scope.snapshots, scope.releaseId, clock = clock, feedback = restarted)
            assertEquals(saved, plans.byId(scope.request.userId, scope.setId, date, 7))
            assertNull(plans.byId(scope.request.userId + 1, scope.setId, date, 7))
            val answerId = scope.runner.read { CuratedWritingFeedbackTable.selectAll().single()[CuratedWritingFeedbackTable.answerId] }
            assertEquals("WRITING_ANSWER_NOT_FOUND", assertFails {
                plans.retryFeedback(scope.request.copy(userId = scope.request.userId + 1), answerId)
            }.message)
            assertFails { plans.submit(scope.request, scope.itemId, scope.answer, "0".repeat(64)) }
            assertEquals(4, model.commands.size)
            scope.runner.read {
                val row = CuratedWritingFeedbackTable.selectAll().single()
                assertEquals("curated-writing-feedback-v14", row[CuratedWritingFeedbackTable.policyVersion])
                assertEquals(CuratedWritingManifestCodec.sha256("${scope.request.userId}|${row[CuratedWritingFeedbackTable.answerId]}|" +
                    "${row[CuratedWritingFeedbackTable.answerHash]}|${row[CuratedWritingFeedbackTable.sourceHash]}|" +
                    "curated-writing-feedback-v14|${result.string("meaningContractHash")}|server-repair-plan-v1"),
                    row[CuratedWritingFeedbackTable.requestHash])
                assertEquals(40, row[CuratedWritingFeedbackTable.inputTokens])
                assertEquals(20, row[CuratedWritingFeedbackTable.outputTokens])
                assertEquals(0L, WritingEvaluationsTable.selectAll().count())
                assertTrue(GrowthProfilesTable.selectAll().all { it[GrowthProfilesTable.evaluationCount] == 0 })
            }
        }
    }

    @Test
    fun `공유 마감 C1 C2는 모델 core 없이 실제 DB에 단일 결정적 교정으로 저장한다`() {
        val model = CuratedWritingSemanticV14TestModel()
        scenario(model, answer = CuratedWritingSemanticTestModel.answer.replace("午後5時", "18時")) { scope ->
            // 실행: 근거 이후 시각 수리는 서버가 하고, 수정된 유일 segment는 선택 대상에서 제외한다.
            val saved = scope.submit()
            val result = saved.feedback().getValue("result").jsonObject

            // 검증: 수리는 결정적으로 수행하되 적용된 전체 문맥은 별도 검수한다.
            assertEquals(3, model.commands.size)
            assertEquals("writing_core_verification", model.commands.last().schemaName)
            assertEquals("NOT_APPLICABLE", result.string("optionalSuggestionsStatus"))
            assertTrue(model.commands.none { it.schemaName in setOf("writing_server_repairs", "writing_server_repair_remaining") })
            assertEquals(1, result.getValue("necessaryCorrections").jsonArray.size)
            assertEquals("17時", result.getValue("necessaryCorrections").jsonArray.single().jsonObject
                .getValue("patch").jsonObject.string("replacement"))
            assertEquals(mapOf("C1" to "UNMET", "C2" to "UNMET", "C3" to "MET"),
                result.getValue("observations").jsonArray.associate { it.jsonObject.string("requirementId") to it.jsonObject.string("status") })
        }
    }

    @Test
    fun `선택 검수 거절 중복 모델실패는 핵심 성공을 유지하며 자동 재전송하지 않는다`() {
        for ((mode, calls) in listOf(CuratedWritingSemanticV14TestModel.OptionalMode.REJECT to 4,
            CuratedWritingSemanticV14TestModel.OptionalMode.DUPLICATE to 3,
            CuratedWritingSemanticV14TestModel.OptionalMode.THROW to 3)) {
            val model = CuratedWritingSemanticV14TestModel(optionalMode = mode)
            scenario(model) { scope ->
                // 실행: 서로 다른 선택 단계 실패를 실제 저장까지 통과시킨다.
                val saved = scope.submit()
                val feedback = saved.feedback()
                val result = feedback.getValue("result").jsonObject

                // 검증: 필수 결과는 성공, 선택만 실패이며 조회·중복 제출은 재과금하지 않는다.
                assertEquals("SUCCEEDED", feedback.string("status"))
                assertEquals("FAILED", result.string("optionalSuggestionsStatus"))
                assertTrue(result.string("optionalSuggestionsFailureCode").matches(Regex("[A-Z][A-Z0-9_]{0,79}")))
                assertTrue(result.getValue("optionalAlternatives").jsonArray.isEmpty())
                assertTrue(result.getValue("necessaryCorrections").jsonArray.isEmpty())
                assertEquals(calls, model.commands.size)
                assertEquals(saved, scope.submit())
                assertEquals(calls, model.commands.size)
            }
        }
    }

    @Test
    fun `선택 단계 모델 변경은 핵심 성공에 흡수하지 않고 전체 실패로 저장한다`() {
        val model = CuratedWritingSemanticV14TestModel(optionalMode = CuratedWritingSemanticV14TestModel.OptionalMode.MODEL_CHANGED)
        scenario(model) { scope ->
            // 실행: 선택 생성 결과만 승인되지 않은 모델 식별자로 돌려준다.
            val saved = scope.submit()
            val feedback = saved.feedback()

            // 검증: 모델 정책 위반은 일반 선택 실패와 구분하고 독립검수·자동 재전송을 하지 않는다.
            assertEquals("FAILED", feedback.string("status"))
            assertEquals("FEEDBACK_VALIDATION_FAILED", feedback.string("failureCode"))
            assertEquals(JsonNull, feedback.getValue("result"))
            assertEquals(3, model.commands.size)
            assertTrue(model.commands.none { it.schemaName == "writing_optional_verification" })
            assertEquals(saved, scope.submit())
            assertEquals(3, model.commands.size)
            scope.runner.read {
                val row = CuratedWritingFeedbackTable.selectAll().single()
                assertNull(row[CuratedWritingFeedbackTable.resultJson])
                assertEquals(0L, WritingEvaluationsTable.selectAll().count())
            }
        }
    }

    @Test
    fun `실패 문법 작업만 한 번 복구하고 선택 예산 부족은 별도 저장한다`() {
        val model = CuratedWritingSemanticV14TestModel(coreMode = CuratedWritingSemanticV14TestModel.CoreMode.REPAIR_ONCE)
        // 준비: 두 실제 문법 오류와 별도로 미수정 정상 문장을 남겨 optional 호출 예산 분기에 도달한다.
        val answer = CuratedWritingSemanticV14TestModel.freeTwoErrors + "ご検討をお願いいたします。"
        scenario(model, free = true, answer = answer) { scope ->
            // 실행: 실제 위치의 두 문법 작업 중 하나만 첫 시도에서 실패한다.
            val result = scope.submit().feedback().getValue("result").jsonObject

            // 검증: 정상 R1은 freeze되고 미완료 R2만 복구하며 optional 추가 호출은 없다.
            assertEquals(5, model.commands.size)
            assertEquals(listOf("writing_observed_claims", "writing_atom_evidence_v14", "writing_server_repairs",
                "writing_server_repair_remaining", "writing_core_verification"), model.commands.map { it.schemaName })
            val repairInput = Json.parseToJsonElement(model.commands.single {
                it.schemaName == "writing_server_repair_remaining"
            }.messages.single().content).jsonObject
            assertEquals(1, repairInput.getValue("repairTasks").jsonArray.size)
            assertEquals(1, repairInput.getValue("frozenRepairs").jsonObject.size)
            assertEquals(2, result.getValue("necessaryCorrections").jsonArray.size)
            assertEquals("NOT_RUN_LIMIT", result.string("optionalSuggestionsStatus"))
            assertEquals("OPTIONAL_CALL_LIMIT", result.string("optionalSuggestionsFailureCode"))
        }
    }

    @Test
    fun `범위 위조 core 반복실패 추가작업은 실제 실패로 저장하고 선택 단계에 진입하지 않는다`() {
        val scenarios = listOf(
            Triple(CuratedWritingSemanticV14TestModel(invalidRange = true), 2, CuratedWritingSemanticTestModel.answer),
            Triple(CuratedWritingSemanticV14TestModel(coreMode = CuratedWritingSemanticV14TestModel.CoreMode.FAIL), 4,
                CuratedWritingSemanticV14TestModel.freeOneError),
            Triple(CuratedWritingSemanticV14TestModel(coreMode = CuratedWritingSemanticV14TestModel.CoreMode.EXTRA_TASK), 3,
                CuratedWritingSemanticV14TestModel.freeOneError),
        )
        scenarios.forEachIndexed { index, (model, count, answer) ->
            scenario(model, free = index > 0, answer = answer) { scope ->
                // 실행: 근거 또는 핵심 수리 실패를 보존한다.
                val saved = scope.submit()
                assertEquals("FAILED", saved.feedback().string("status"))
                assertEquals("FEEDBACK_VALIDATION_FAILED", saved.feedback().string("failureCode"))

                // 검증: 실패를 성공으로 바꾸거나 선택 호출·동일 제출 재시도로 숨기지 않는다.
                assertEquals(count, model.commands.size)
                assertTrue(model.commands.none { it.schemaName?.startsWith("writing_optional_") == true })
                assertEquals(saved, scope.submit())
                assertEquals(count, model.commands.size)
                scope.runner.read { assertNull(CuratedWritingFeedbackTable.selectAll().single()[CuratedWritingFeedbackTable.resultJson]) }
            }
        }
    }

    @Test
    fun `core와 선택 두 단계 합계는 다섯 호출을 넘지 않는다`() {
        val model = CuratedWritingSemanticV14TestModel(optionalMode = CuratedWritingSemanticV14TestModel.OptionalMode.VALID)
        scenario(model, free = true, answer = CuratedWritingSemanticV14TestModel.freeOneError) { scope ->
            // 실행: 자연어 문법 수리 한 번 뒤 필수 문맥을 독립 검수한다.
            val result = scope.submit().feedback().getValue("result").jsonObject

            // 검증: 모델·tier·token/호출 상한과 실제 저장 결과를 함께 확인한다.
            assertEquals(4, model.commands.size)
            assertEquals("writing_core_verification", model.commands.last().schemaName)
            assertTrue(model.commands.all { it.tier == ModelTier.MINI && it.maxOutputTokens == 4096 && it.strict })
            assertEquals("NOT_RUN_LIMIT", result.string("optionalSuggestionsStatus"))
            assertEquals("OPTIONAL_CALL_LIMIT", result.string("optionalSuggestionsFailureCode"))
            assertEquals(1, result.getValue("necessaryCorrections").jsonArray.size)
            assertTrue(result.getValue("optionalAlternatives").jsonArray.isEmpty())
            assertTrue(model.commands.none { it.schemaName?.startsWith("writing_optional_") == true })
        }
    }

    @Test
    fun `부분 활용 범위는 전체 적용문장에서 추출하고 마지막 음절을 중복하지 않는다`() {
        val model = CuratedWritingSemanticV14TestModel()
        val answer = "明日の午後に商品を取り置きしていただけますか。先日の説明が印象に残るました。"
        scenario(model, free = true, answer = answer) { scope ->
            // 실행: 실제 N04와 같은 마지막 た를 제외한 오류 범위를 받는다.
            val result = scope.submit().feedback().getValue("result").jsonObject
            val correction = result.getValue("necessaryCorrections").jsonArray.single().jsonObject

            // 검증: prefix/suffix를 보존한 전체 문장을 서버가 국소 patch로 추출하고 독립 검수한다.
            assertEquals("るまし", correction.getValue("patch").jsonObject.string("original"))
            assertEquals("りまし", correction.getValue("patch").jsonObject.string("replacement"))
            assertEquals("先日の説明が印象に残りました。", correction.string("replacement"))
            assertFalse(correction.string("replacement").contains("残りましたた"))
            assertEquals(4, model.commands.size)
            assertEquals("writing_core_verification", model.commands.last().schemaName)
        }
    }

    @Test
    fun `새 문법 오류를 만든 필수 교정은 선택 실패로 숨기지 않고 전체 실패로 저장한다`() {
        val model = CuratedWritingSemanticV14TestModel(
            coreMode = CuratedWritingSemanticV14TestModel.CoreMode.DUPLICATED_ENDING,
            rejectCoreVerification = true,
        )
        val answer = "明日の午後に商品を取り置きしていただけますか。先日の説明が印象に残るました。"
        scenario(model, free = true, answer = answer) { scope ->
            // 실행: 원래 문제였던 残りましたた를 생성하고 필수 독립 검수는 이를 거절한다.
            val saved = scope.submit()
            val feedback = saved.feedback()

            // 검증: 잘못된 교정/선택 성공을 노출하지 않으며 동일 제출은 추가 호출이 없다.
            assertEquals("FAILED", feedback.string("status"))
            assertEquals("FEEDBACK_VALIDATION_FAILED", feedback.string("failureCode"))
            assertEquals(JsonNull, feedback.getValue("result"))
            assertEquals(4, model.commands.size)
            assertEquals("writing_core_verification", model.commands.last().schemaName)
            assertTrue(model.commands.none { it.schemaName?.startsWith("writing_optional_") == true })
            assertEquals(saved, scope.submit())
            assertEquals(4, model.commands.size)
            scope.runner.read { assertNull(CuratedWritingFeedbackTable.selectAll().single()[CuratedWritingFeedbackTable.resultJson]) }
        }
    }

    @Test
    fun `V14 저장 실패 후 타인 거절과 본인 무호출 복구는 선택 상태까지 보존한다`() {
        val model = CuratedWritingSemanticV14TestModel(optionalMode = CuratedWritingSemanticV14TestModel.OptionalMode.REJECT)
        scenario(model) { scope ->
            // 준비: 오직 이 scratch catalog의 최종 성공 UPDATE만 거절한다.
            scope.connection { it.execute("""
                CREATE TRIGGER qa_v14_save_failure BEFORE UPDATE ON language_learning_curated_writing_feedback
                FOR EACH ROW BEGIN IF NEW.status = 'SUCCEEDED' THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'qa_owned_v14_save_failure';
                END IF; END
            """.trimIndent()) }
            try {
                // 실행: 네 모델 단계는 정산됐지만 최종 DB 저장만 실패한다.
                val failure = assertFails { scope.submit() }
                assertTrue(generateSequence(failure as Throwable?) { it.cause }.filterIsInstance<SQLException>().any { it.sqlState == "45000" })
                assertEquals(4, model.commands.size)
                val answerId = scope.runner.read { CuratedWritingFeedbackTable.selectAll().single()[CuratedWritingFeedbackTable.answerId] }
                assertEquals("WRITING_ANSWER_NOT_FOUND", assertFails {
                    scope.plans.retryFeedback(scope.request.copy(userId = scope.request.userId + 1), answerId)
                }.message)
                assertTrue(assertNotNull(scope.plans.byId(scope.request.userId, scope.setId, date, 7)).feedback()
                    .getValue("canRetry").jsonPrimitive.boolean)
                scope.connection { it.execute("DROP TRIGGER qa_v14_save_failure") }

                // 검증: 이미 검증한 핵심/선택 실패 상태를 그대로 저장하며 모델을 다시 부르지 않는다.
                val recovered = scope.plans.retryFeedback(scope.request, answerId).feedback()
                assertEquals("SUCCEEDED", recovered.string("status"))
                assertEquals("FAILED", recovered.getValue("result").jsonObject.string("optionalSuggestionsStatus"))
                assertEquals(4, model.commands.size)
            } finally { scope.connection { it.execute("DROP TRIGGER IF EXISTS qa_v14_save_failure") } }
        }
    }

    @Test
    fun `V14 마지막 모델 응답 전 계획 삭제는 늦은 저장과 재시도로 복원되지 않는다`() {
        val model = CuratedWritingSemanticV14TestModel(optionalMode = CuratedWritingSemanticV14TestModel.OptionalMode.VALID)
        scenario(model) { scope ->
            // 준비: 마지막 독립검수 응답이 돌아오기 직전에 실제 owner/revision 삭제 경로를 실행한다.
            var answerId = 0L
            model.beforeReturn = { command ->
                if (command.schemaName == "writing_optional_verification") {
                    answerId = scope.runner.read { CuratedWritingFeedbackTable.selectAll().single()[CuratedWritingFeedbackTable.answerId] }
                    assertTrue(scope.plans.delete(scope.request, scope.setId, 1).getValue("deleted").jsonPrimitive.boolean)
                }
            }

            // 실행: 모델 결과가 유효해도 삭제된 답안에 저장하거나 재시도로 되살릴 수 없다.
            assertEquals("WRITING_ANSWER_NOT_FOUND", assertFails { scope.submit() }.message)
            assertTrue(answerId > 0)
            assertEquals("WRITING_ANSWER_NOT_FOUND", assertFails { scope.plans.retryFeedback(scope.request, answerId) }.message)

            // 검증: 삭제 이후 추가 호출 없이 답안·피드백·활동이 없고 공식 평가도 생성되지 않는다.
            assertEquals(4, model.commands.size)
            assertNull(scope.plans.byId(scope.request.userId, scope.setId, date, 7))
            scope.runner.read {
                assertEquals(0L, CuratedSetsTable.selectAll().count())
                assertEquals(0L, CuratedAnswersTable.selectAll().count())
                assertEquals(0L, CuratedWritingFeedbackTable.selectAll().count())
                assertEquals(0L, GrowthActivitiesTable.selectAll().count())
                assertEquals(0L, WritingEvaluationsTable.selectAll().count())
            }
        }
    }

    private data class Scope(val runner: JdbcTransactionRunner, val snapshots: CuratedWritingStore,
                             val plans: CuratedWritingPlanStore, val request: CuratedWritingStore.Start,
                             val itemId: Long, val revision: String, val setId: Long, val answer: String,
                             val releaseId: String, val connection: ((java.sql.Statement) -> Unit) -> Unit) {
        suspend fun submit(): JsonObject = plans.submit(request, itemId, answer, revision)
    }

    private fun scenario(model: CuratedWritingSemanticV14TestModel, free: Boolean = false,
                         answer: String = CuratedWritingSemanticTestModel.answer, block: suspend (Scope) -> Unit) = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory -> runBlocking {
            val runner = JdbcTransactionRunner(factory.database, 4)
            val manifest = CuratedWritingSemanticV14TestModel.manifest(free)
            val snapshots = CuratedWritingStore(runner, manifest, clock)
            snapshots.importManifest()
            val user = 9513L
            listOf(user, user + 1).forEach { owner -> ExposedWritingSetUnitOfWork(runner, clock).write(owner) {
                val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                growth.saveProfile(GrowthProfile(userId = owner, state = "ACTIVE", baseLevelScore = 60.0, createdAt = now, updatedAt = now))
            } }
            val worker = CuratedWritingFeedbackWorker(runner, snapshots, manifest.releaseId, model, clock)
            val plans = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId, clock = clock, feedback = worker)
            val request = CuratedWritingStore.Start(user, date, if (free) WritingType.FREE else WritingType.TRANSLATION,
                "ko", "ja", false, true, 7)
            val started = plans.start(request, 1, false)
            val item = started.getValue("items").jsonArray.single().jsonObject
            block(Scope(runner, snapshots, plans, request, LearningPublicId.decode(item.string("itemId")),
                item.string("contentRevision"), LearningPublicId.decode(started.string("dailySetId")), answer, manifest.releaseId) { operation ->
                db.connect().use { connection -> connection.createStatement().use(operation) }
            })
        } }
    }

    companion object {
        private val date = LocalDate.parse("2026-10-18")
        private val clock = Clock.fixed(Instant.parse("2026-10-18T03:00:00Z"), ZoneOffset.UTC)
    }
}

private fun JsonObject.feedback(): JsonObject = getValue("items").jsonArray.single().jsonObject
    .getValue("attempts").jsonArray.single().jsonObject.getValue("feedback").jsonObject
private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
