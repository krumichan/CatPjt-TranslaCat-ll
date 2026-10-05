package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthProfile
import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.table.GrowthProfilesTable
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingPlanFixtures
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
    fun `세 답안 중 실패한 두 답안만 재시도하고 성공 결과와 공식 평가를 보존한다`() = LocalScratchMysql.use { db ->
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
                val answer = input.getValue("answer").jsonPrimitive.content
                calls[answer] = (calls[answer] ?: 0) + 1
                val failure = answer.startsWith("失敗") && calls.getValue(answer) == 1
                val observations = if (failure) buildJsonArray {} else buildJsonArray {
                    input.getValue("requirements").jsonArray.forEach { requirement -> add(buildJsonObject {
                        put("requirementId", requirement.jsonObject.getValue("id"))
                        put("status", "MET")
                        put("segmentIds", buildJsonArray { add("S1") })
                        put("originText", "답안에서 요구 내용을 확인했습니다.")
                        put("learningText", "回答から必要な内容を確認しました。")
                    }) }
                }
                ModelExecutionResult(buildJsonObject {
                    put("observations", observations)
                    put("necessaryCorrections", buildJsonArray {})
                    put("optionalAlternatives", buildJsonArray {})
                    put("uncertainties", buildJsonArray {})
                }, 200, 100, "openai", "gpt-5-mini-2025-08-07")
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
            val successfulHash = runner.read { CuratedWritingFeedbackTable.selectAll().where {
                CuratedWritingFeedbackTable.answerId eq answerIds.first()
            }.single()[CuratedWritingFeedbackTable.resultHash] }

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
                assertEquals(0L, WritingEvaluationsTable.selectAll().count())
                assertEquals(0, GrowthProfilesTable.selectAll().single()[GrowthProfilesTable.evaluationCount])
            }
            assertFailsWith<IllegalStateException> { plans.retryFeedback(request.copy(userId = user + 1), answerIds.first()) }
        } }
    }
}
