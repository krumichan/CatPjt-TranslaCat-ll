package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthProfile
import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.table.GrowthProfilesTable
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingPlanFixtures
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingManifestCodec
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.*
import jp.co.translacat.languagelearning.shared.ai.*
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

/** 합성 Provider를 쓰되 실제 MySQL worker·claim·결과 저장 경로를 검증한다. */
class CuratedWritingStoreIntegrationTestFeedback {
    private val clock = Clock.fixed(Instant.parse("2026-10-04T03:00:00Z"), ZoneOffset.UTC)
    private val date = LocalDate.parse("2026-10-04")

    @Test
    fun `지원하지 않는 v1 v2 실패는 원본을 보존하고 재시도를 표시하지 않는다`() {
        listOf("curated-writing-feedback-v1", "curated-writing-feedback-v2").forEach(::assertLegacyFailurePreserved)
    }

    @Test
    fun `지원하는 v3 실패는 저장된 정책의 모델 계약으로 명시적 재시도한다`() {
        assertLegacyFailurePreserved("curated-writing-feedback-v3", supportedRetry = true)
    }

    @Test
    fun `지원하는 v4 실패는 선택 관계 필드를 강제하지 않는 원래 계약으로 재시도한다`() {
        assertLegacyFailurePreserved("curated-writing-feedback-v4", supportedRetry = true)
    }

    @Test
    fun `지원하는 v5 실패는 기존 선택 관계 계약으로 재시도한다`() {
        assertLegacyFailurePreserved("curated-writing-feedback-v5", supportedRetry = true)
    }

    @Test
    fun `지원하는 v6 실패는 경계 공백을 복원하지 않는 당시 계약으로 재시도한다`() {
        assertLegacyFailurePreserved("curated-writing-feedback-v6", supportedRetry = true)
    }

    @Test
    fun `지원하는 v7 실패는 source unit을 강제하지 않는 당시 계약으로 재시도한다`() {
        assertLegacyFailurePreserved("curated-writing-feedback-v7", supportedRetry = true)
    }

    @Test
    fun `지원하는 v8 실패도 원래 source unit 계약으로만 재시도한다`() {
        assertLegacyFailurePreserved("curated-writing-feedback-v8", supportedRetry = true)
    }

    private fun assertLegacyFailurePreserved(legacyPolicy: String) = assertLegacyFailurePreserved(legacyPolicy, false)

    private fun assertLegacyFailurePreserved(legacyPolicy: String, supportedRetry: Boolean) = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory -> runBlocking {
            // 준비: 합성 실패 답안 한 건을 과거 v1 실패 행의 형태로 준비한다. 실제 성공 결과는 변경하지 않는다.
            val runner = JdbcTransactionRunner(factory.database, 4)
            val manifest = CuratedWritingPlanFixtures.manifest(3)
            val snapshots = CuratedWritingStore(runner, manifest, clock)
            snapshots.importManifest()
            val user = 9111L
            ExposedWritingSetUnitOfWork(runner, clock).write(user) {
                val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                growth.saveProfile(GrowthProfile(userId = user, state = "ACTIVE", baseLevelScore = 60.0,
                    createdAt = now, updatedAt = now))
            }
            var calls = 0
            val model = ModelExecutionPort { command ->
                calls++
                val input = Json.parseToJsonElement(command.messages.single().content).jsonObject
                if (calls == 1) {
                    // 새 답안의 언어는 저장된 plan과 일치하며 첫 실패는 합성 schema 오류다.
                    assertEquals("ko", input.getValue("task").jsonObject.getValue("originLanguage").jsonPrimitive.content)
                    ModelExecutionResult(buildJsonObject {}, 1, 1, "openai", "gpt-5-mini")
                } else {
                    assertTrue(supportedRetry)
                    val typed = legacyPolicy in setOf("curated-writing-feedback-v4", "curated-writing-feedback-v5", "curated-writing-feedback-v6", "curated-writing-feedback-v7", "curated-writing-feedback-v8")
                    assertEquals(typed, "originLanguage" in input.getValue("task").jsonObject)
                    val schema = checkNotNull(command.responseSchema).getValue("properties").jsonObject
                        .getValue("observations").jsonObject.getValue("items").jsonObject.getValue("properties").jsonObject
                    assertEquals(typed, "relationChecks" in schema)
                    assertEquals(!typed, "originText" in schema)
                    val structured = legacyPolicy in setOf("curated-writing-feedback-v6", "curated-writing-feedback-v7", "curated-writing-feedback-v8")
                    assertEquals(structured, "requirementParts" in schema)
                    if (structured) {
                        val partSchema = schema.getValue("requirementParts").jsonObject.getValue("items").jsonObject.getValue("properties").jsonObject
                        assertEquals(legacyPolicy != "curated-writing-feedback-v8", "requirementQuote" in partSchema)
                        assertEquals(legacyPolicy == "curated-writing-feedback-v8", "startUnitId" in partSchema)
                        assertEquals(legacyPolicy == "curated-writing-feedback-v8", "taskConditionIds" in partSchema)
                    }
                    val optionalSchema = checkNotNull(command.responseSchema).getValue("properties").jsonObject
                        .getValue("optionalAlternatives").jsonObject.getValue("items").jsonObject.getValue("properties").jsonObject
                    assertEquals(legacyPolicy in setOf("curated-writing-feedback-v5", "curated-writing-feedback-v6", "curated-writing-feedback-v7", "curated-writing-feedback-v8"), "relationChecks" in optionalSchema)
                    ModelExecutionResult(buildJsonObject {
                        put("observations", buildJsonArray {
                            input.getValue("task").jsonObject.getValue("requirements").jsonArray.forEach { required ->
                                if (structured) {
                                    add(CuratedWritingFeedbackTestPayload.observation(input, required.jsonObject))
                                } else add(buildJsonObject {
                                    put("requirementId", required.jsonObject.getValue("id"))
                                    put("status", "MET"); put("evidenceKind", "LEARNER_TEXT")
                                    put("segmentIds", buildJsonArray { add("S1") })
                                    if (typed) put("relationChecks", buildJsonObject {
                                        listOf("causality", "chronology", "specificity", "stance").forEach { put(it, "PRESERVED") }
                                    }) else {
                                        put("originText", "요구에 대응하는 답안을 확인했습니다.")
                                        put("learningText", "要件に対応する回答を確認しました。")
                                    }
                                })
                            }
                        })
                        put("necessaryCorrections", buildJsonArray {})
                        put("optionalAlternatives", buildJsonArray {})
                        put("uncertainties", buildJsonArray {})
                    }, 1, 1, "openai", "gpt-5-mini")
                }
            }
            val worker = CuratedWritingFeedbackWorker(runner, snapshots, manifest.releaseId, model, clock)
            val plans = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId, clock = clock, feedback = worker)
            val request = CuratedWritingStore.Start(user, date, WritingType.TRANSLATION, "ko", "ja", false, true, 7)
            val started = plans.start(request, 1, false)
            val item = started.getValue("items").jsonArray.single().jsonObject
            val saved = plans.submit(request, LearningPublicId.decode(item.getValue("itemId").jsonPrimitive.content),
                "学習の回答です。", item.getValue("contentRevision").jsonPrimitive.content)
            val attempt = saved.getValue("items").jsonArray.single().jsonObject.getValue("attempts").jsonArray.single().jsonObject
            val answerId = LearningPublicId.decode(attempt.getValue("answerId").jsonPrimitive.content)
            assertEquals("FAILED", attempt.getValue("feedback").jsonObject.getValue("status").jsonPrimitive.content)
            assertTrue(attempt.getValue("feedback").jsonObject.getValue("canRetry").jsonPrimitive.boolean)
            val preservedRequestHash = runner.write {
                val row = CuratedWritingFeedbackTable.selectAll().where { CuratedWritingFeedbackTable.answerId eq answerId }.single()
                val legacyHash = CuratedWritingManifestCodec.sha256(
                    "$user|$answerId|${row[CuratedWritingFeedbackTable.answerHash]}|" +
                        "${row[CuratedWritingFeedbackTable.sourceHash]}|$legacyPolicy",
                )
                CuratedWritingFeedbackTable.update({ CuratedWritingFeedbackTable.answerId eq answerId }) {
                    it[policyVersion] = legacyPolicy
                    it[requestHash] = legacyHash
                }
                legacyHash
            }

            // 실행: 조회와 거절된 명시적 재시도는 모델을 다시 호출하거나 옛 hash를 바꾸지 않는다.
            val state = runner.read {
                val row = CuratedWritingFeedbackTable.selectAll().where { CuratedWritingFeedbackTable.answerId eq answerId }.single()
                worker.publicState(row)
            }
            if (supportedRetry) worker.retry(request, answerId)
            else {
                val denied = assertFailsWith<IllegalArgumentException> { worker.retry(request, answerId) }
                assertEquals("WRITING_FEEDBACK_POLICY_CONFLICT", denied.message)
            }

            // 검증
            assertEquals("FAILED", state.getValue("status").jsonPrimitive.content)
            assertEquals(supportedRetry, state.getValue("canRetry").jsonPrimitive.boolean)
            assertEquals(if (supportedRetry) 2 else 1, calls)
            runner.read {
                val row = CuratedWritingFeedbackTable.selectAll().where { CuratedWritingFeedbackTable.answerId eq answerId }.single()
                assertEquals(legacyPolicy, row[CuratedWritingFeedbackTable.policyVersion])
                assertEquals(if (supportedRetry) "SUCCEEDED" else "FAILED", row[CuratedWritingFeedbackTable.status])
                assertEquals(preservedRequestHash, row[CuratedWritingFeedbackTable.requestHash])
                if (supportedRetry) {
                    val result = Json.parseToJsonElement(checkNotNull(row[CuratedWritingFeedbackTable.resultJson])).jsonObject
                    assertEquals(legacyPolicy, result.getValue("policyVersion").jsonPrimitive.content)
                    assertEquals(legacyPolicy in setOf("curated-writing-feedback-v4", "curated-writing-feedback-v5", "curated-writing-feedback-v6", "curated-writing-feedback-v7", "curated-writing-feedback-v8"),
                        "relationChecks" in result.getValue("observations").jsonArray.first().jsonObject)
                } else assertNull(row[CuratedWritingFeedbackTable.resultJson])
            }
        } }
    }

    @Test
    fun `세 답안 중 실패한 두 답안만 재시도하고 과거 성공 결과와 공식 평가를 보존한다`() {
        listOf("curated-writing-feedback-v1", "curated-writing-feedback-v2", "curated-writing-feedback-v3",
            "curated-writing-feedback-v4", "curated-writing-feedback-v5", "curated-writing-feedback-v6", "curated-writing-feedback-v7", "curated-writing-feedback-v8").forEach(::assertSavedPolicyPreserved)
    }

    private fun assertSavedPolicyPreserved(legacyPolicy: String) = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory -> runBlocking {
            // 준비: 승인 없는 QA DRAFT 세 문항과 현재 owner. 첫 시도의 두 답안만 스키마 실패로 주입한다.
            val runner = JdbcTransactionRunner(factory.database, 4)
            val manifest = CuratedWritingPlanFixtures.manifest(3)
            val snapshots = CuratedWritingStore(runner, manifest, clock)
            snapshots.importManifest()
            val user = 9101L
            ExposedWritingSetUnitOfWork(runner, clock).write(user) {
                val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                growth.saveProfile(GrowthProfile(userId = user, state = "ACTIVE", baseLevelScore = 60.0,
                    createdAt = now, updatedAt = now))
            }
            val calls = mutableMapOf<String, Int>()
            val model = ModelExecutionPort { command ->
                val input = Json.parseToJsonElement(command.messages.single().content).jsonObject
                val answer = input.getValue("learnerAnswer").jsonObject.getValue("text").jsonPrimitive.content
                calls[answer] = (calls[answer] ?: 0) + 1
                val failure = answer.startsWith("失敗") && calls.getValue(answer) == 1
                ModelExecutionResult(CuratedWritingFeedbackTestPayload.v8Response(input, failure),
                    200, 100, "openai", "gpt-5-mini-2025-08-07")
            }
            val worker = CuratedWritingFeedbackWorker(runner, snapshots, manifest.releaseId, model, clock)
            val plans = CuratedWritingPlanStore(runner, snapshots, manifest.releaseId, clock = clock, feedback = worker)
            val request = CuratedWritingStore.Start(user, date, WritingType.TRANSLATION, "ko", "ja", false, true, 7)
            val started = plans.start(request, 3, false)
            val items = started.getValue("items").jsonArray.map { it.jsonObject }

            // 실행: 정상1·실패2 저장, 중복 제출과 GET은 추가 모델 호출을 만들지 않는다.
            val answers = listOf("正常な回答です。", "失敗する回答です。", "失敗する別の回答です。")
            var saved = started
            items.zip(answers).forEach { (item, answer) ->
                saved = plans.submit(request, LearningPublicId.decode(item.getValue("itemId").jsonPrimitive.content),
                    answer, item.getValue("contentRevision").jsonPrimitive.content)
            }
            val setId = LearningPublicId.decode(started.getValue("dailySetId").jsonPrimitive.content)
            assertEquals(saved, plans.byId(user, setId, date, 7))
            assertEquals(1, saved.getValue("feedbackCompletedItemCount").jsonPrimitive.int)
            assertEquals(listOf(1, 1, 1), answers.map { calls[it] })
            val before = saved.getValue("items").jsonArray.map { it.jsonObject }
            val answerIds = before.map { item -> LearningPublicId.decode(item.getValue("attempts").jsonArray.single()
                .jsonObject.getValue("answerId").jsonPrimitive.content) }
            // 준비: scratch DB의 합성 성공 결과 하나를 과거 정책의 실제 형태로 만들어 업그레이드 경계를 시험한다.
            runner.write {
                val row = CuratedWritingFeedbackTable.selectAll().where {
                    CuratedWritingFeedbackTable.answerId eq answerIds.first()
                }.single()
                val current = Json.parseToJsonElement(row[CuratedWritingFeedbackTable.resultJson]!!).jsonObject
                val legacy = JsonObject(current + mapOf(
                    "policyVersion" to JsonPrimitive(legacyPolicy),
                    "observations" to JsonArray(current.getValue("observations").jsonArray.map {
                        val old = if (legacyPolicy in setOf("curated-writing-feedback-v6", "curated-writing-feedback-v7")) JsonObject(it.jsonObject +
                            ("requirementParts" to JsonArray(it.jsonObject.getValue("requirementParts").jsonArray.map { part ->
                                JsonObject(part.jsonObject - "taskConditionIds")
                            }))) else if (legacyPolicy == "curated-writing-feedback-v8") it.jsonObject else it.jsonObject - "requirementParts"
                        val oldFields = if (legacyPolicy in setOf("curated-writing-feedback-v4", "curated-writing-feedback-v5", "curated-writing-feedback-v6", "curated-writing-feedback-v7", "curated-writing-feedback-v8")) old else old - "relationChecks"
                        JsonObject(if (legacyPolicy == "curated-writing-feedback-v1") oldFields - "evidenceKind" else oldFields)
                    }),
                ))
                val hash = CuratedWritingManifestCodec.sha256(
                    "$user|${answerIds.first()}|${row[CuratedWritingFeedbackTable.answerHash]}|" +
                        "${row[CuratedWritingFeedbackTable.sourceHash]}|$legacyPolicy",
                )
                CuratedWritingFeedbackTable.update({ CuratedWritingFeedbackTable.answerId eq answerIds.first() }) {
                    it[policyVersion] = legacyPolicy
                    it[requestHash] = hash
                    it[resultJson] = legacy.toString()
                    it[resultHash] = CuratedWritingManifestCodec.sha256(legacy.toString())
                }
            }
            val successfulHash = runner.read { CuratedWritingFeedbackTable.selectAll().where {
                CuratedWritingFeedbackTable.answerId eq answerIds.first()
            }.single()[CuratedWritingFeedbackTable.resultHash] }

            // 실행·검증: 새 worker도 기존 v1/v2 성공 답안의 중복 제출을 재호출하거나 hash 갱신하지 않는다.
            val first = items.first()
            plans.submit(request, LearningPublicId.decode(first.getValue("itemId").jsonPrimitive.content),
                answers.first(), first.getValue("contentRevision").jsonPrimitive.content)
            assertEquals(1, calls[answers.first()])

            // 실행: 실패한 두 답안만 명시적으로 재시도한다.
            answerIds.drop(1).forEach { plans.retryFeedback(request, it) }
            val complete = checkNotNull(plans.byId(user, setId, date, 7))

            // 검증: 정상 답안/결과 hash와 세 답안, 공식 점수·평가 행을 보존한다.
            assertEquals(3, complete.getValue("feedbackCompletedItemCount").jsonPrimitive.int)
            assertEquals(listOf(1, 2, 2), answers.map { calls[it] })
            assertEquals(before.map { it.getValue("originText") }, complete.getValue("items").jsonArray.map { it.jsonObject.getValue("originText") })
            runner.read {
                assertEquals(3L, CuratedAnswersTable.selectAll().count())
                assertEquals(3L, CuratedWritingFeedbackTable.selectAll().count())
                assertEquals(successfulHash, CuratedWritingFeedbackTable.selectAll().where {
                    CuratedWritingFeedbackTable.answerId eq answerIds.first()
                }.single()[CuratedWritingFeedbackTable.resultHash])
                assertEquals(legacyPolicy, CuratedWritingFeedbackTable.selectAll().where {
                    CuratedWritingFeedbackTable.answerId eq answerIds.first()
                }.single()[CuratedWritingFeedbackTable.policyVersion])
                assertEquals(0L, WritingEvaluationsTable.selectAll().count())
                assertEquals(0, GrowthProfilesTable.selectAll().single()[GrowthProfilesTable.evaluationCount])
            }
            assertFailsWith<IllegalStateException> { plans.retryFeedback(request.copy(userId = user + 1), answerIds.first()) }
        } }
    }
}
