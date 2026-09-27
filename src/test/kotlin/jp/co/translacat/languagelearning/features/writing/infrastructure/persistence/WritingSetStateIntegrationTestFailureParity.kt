package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.writing.application.*
import jp.co.translacat.languagelearning.features.writing.domain.model.*
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionFailure
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionPort
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionResult
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.LocalDate
import kotlin.test.*

class WritingSetStateIntegrationTestFailureParity {
    @Test
    fun `원본 평가의 열한 실패는 두 번 시도와 실패 답변 보존 의미를 유지한다`() = LocalScratchMysql.use { db ->
        // 준비: 원본 Python 평가 함수를 직접 실행한 오류 골든과 실제 저장소를 연결한다.
        val fixture = Json.parseToJsonElement(
            checkNotNull(
                javaClass.getResource(
                    "/contracts/writing-evaluation-failure-python-golden.json",
                ),
            ).readText(),
        ).jsonObject
        DatabaseFactory(db.settings()).use { database ->
            val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(database.database, 4))
            runBlocking {
                for ((index, entry) in fixture.getValue("cases").jsonArray.withIndex()) {
                    val case = entry.jsonObject
                    val name = case.getValue("name").jsonPrimitive.content
                    val code = case.getValue("code").jsonPrimitive.content
                    val userId = 910L + index
                    val today = LocalDate.parse("2026-09-26")
                    val generation = WritingGenerationState(work)
                    val set = generation.getOrCreate(
                        NewWritingSet(
                            userId, today, WritingType.FREE,
                            "evaluation-failure-$name", 1, "{}",
                        ),
                    )
                    val claim = assertNotNull(generation.claimNext(userId, set.id))
                    assertTrue(
                        generation.publishItem(
                            userId, set.id, claim,
                            NewWritingItem(
                                1, WritingDifficulty.NORMAL, "합성 평가 실패 검사 문항입니다.", emptyList(),
                                listOf("MEANING"), "합성 검사",
                            ),
                            "synthetic",
                        ),
                    )
                    val item = work.read { items.list(userId, set.id).single() }
                    val answer = WritingAnswerState(work).submit(
                        userId, item.id, "Synthetic answer",
                        null, today, 7, true,
                    )
                    var calls = 0
                    val model = ModelExecutionPort {
                        calls++
                        if (code == "EVALUATION_SCHEMA_INVALID") {
                            ModelExecutionResult(
                                if (name == "non_object") JsonPrimitive("invalid")
                                else JsonObject(emptyMap()),
                                0, 0, "synthetic", "fixed",
                            )
                        } else throw ModelExecutionFailure(
                            code, case.getValue("httpStatus").jsonPrimitive.int,
                            false, failureKind = case["failureKind"]?.jsonPrimitive?.contentOrNull,
                            providerStatus = case["providerStatus"]?.jsonPrimitive?.intOrNull,
                        )
                    }

                    // 실행: 실제 평가 worker가 원본 상한 안에서 실패를 처리하고 상태를 저장한다.
                    val worker = WritingEvaluationWorker(
                        WritingEvaluationState(work),
                        WritingEvaluationExecution(model),
                    )
                    assertFalse(worker.process(userId, answer.id, "ko", "en", today), name)

                    // 검증: 실패를 성공으로 삼키지 않으며 답변·상태·재호출 방지가 유지된다.
                    assertEquals(case.getValue("calls").jsonPrimitive.int, calls, name)
                    val saved = assertNotNull(work.read { answers.findById(userId, answer.id) })
                    assertEquals("Synthetic answer", saved.text, name)
                    assertEquals(WritingEvaluationStatus.FAILED, saved.evaluationStatus, name)
                    assertFalse(worker.process(userId, answer.id, "ko", "en", today), name)
                    assertEquals(case.getValue("calls").jsonPrimitive.int, calls, name)
                }
            }
        }
    }

    @Test
    fun `원본 Python의 일곱 실패 흐름은 호출 수와 외부 오류를 보존한다`() = LocalScratchMysql.use { db ->
        // 준비: 보존된 원본을 직접 실행해 만든 입력·응답·호출 수와 실제 MySQL을 사용한다.
        val fixture = Json.parseToJsonElement(
            checkNotNull(
                javaClass.getResource(
                    "/contracts/writing-failure-python-golden.json",
                ),
            ).readText(),
        ).jsonObject
        val draft = fixture.getValue("draft").jsonObject
        val template = Json.parseToJsonElement(
            checkNotNull(
                javaClass.getResource(
                    "/contracts/writing-acceptance-python-golden.json",
                ),
            ).readText(),
        ).jsonObject
            .getValue("cases").jsonArray.first().jsonObject.getValue("review").jsonObject
        DatabaseFactory(db.settings()).use { database ->
            val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(database.database, 4))
            runBlocking {
                for ((index, entry) in fixture.getValue("cases").jsonArray.withIndex()) {
                    val case = entry.jsonObject
                    val name = case.getValue("case").jsonPrimitive.content
                    val request = JsonObject(
                        fixture.getValue("request").jsonObject +
                            ("requestId" to JsonPrimitive("failure-parity-$name")),
                    )
                    val userId = 810L + index
                    val state = WritingGenerationState(work)
                    val set = state.getOrCreate(
                        NewWritingSet(
                            userId, LocalDate.parse("2026-09-11"),
                            WritingType.TRANSLATION, "failure-parity-$name", 1, request.toString(),
                        ),
                    )
                    val calls = mutableMapOf<String, Int>()
                    val model = ModelExecutionPort { command ->
                        val task = checkNotNull(command.taskName)
                        calls[task] = (calls[task] ?: 0) + 1
                        val generation = command.schemaName == "writing_candidate_batch"
                        val fail = if (generation) name.startsWith("generator_") &&
                            !(name == "generator_refusal_then_valid" && calls[task]!! > 1)
                        else name.startsWith("review_")
                        if (fail) throw when {
                            "configuration" in name -> ModelExecutionFailure("PROVIDER_CONFIGURATION_ERROR", 502, false)
                            "dependency" in name -> ModelExecutionFailure("PROVIDER_UNAVAILABLE", 503, true)
                            else -> ModelExecutionFailure("REFUSAL", 502, false)
                        }
                        val output = if (generation) buildJsonObject { put("items", JsonArray(listOf(draft))) }
                        else {
                            val payload = Json.parseToJsonElement(
                                command.messages.single().content
                                    .substringAfter("<writing-review-data>\n")
                                    .substringBefore("\n</writing-review-data>"),
                            )
                                .jsonObject
                            JsonObject(
                                template + mapOf(
                                    "candidateId" to payload.getValue("candidateId"),
                                    "contentHash" to payload.getValue("contentHash"),
                                    "observedWritingType" to JsonPrimitive("TRANSLATION"),
                                    "estimatedBand" to JsonPrimitive(3),
                                ),
                            )
                        }
                        ModelExecutionResult(output, 0, 0, "synthetic", "fixed-fixture")
                    }

                    // 실행: 모델 출력만 고정하고 실제 생성 worker·검증·상태 게시를 통과한다.
                    val result = WritingGenerationWorker(
                        state, work, WritingGenerationExecution(model),
                        WritingReviewExecution(model),
                    ).process(userId, set.id, request)

                    // 검증: 생성 거부와 필수 검증 실패의 호출 수·HTTP 코드·기존 오류 코드를 대조한다.
                    val expectedCalls = case.getValue("calls").jsonObject.mapValues { it.value.jsonPrimitive.int }
                    assertEquals(expectedCalls, calls, name)
                    val outcome = case.getValue("outcome").jsonObject
                    if (outcome.getValue("status").jsonPrimitive.int == 200) {
                        assertEquals(null, result.failureCode, name)
                        assertEquals(1, work.read { items.count(userId, set.id) }, name)
                    } else {
                        val failure = writingPublicFailure(assertNotNull(result.failureCode), result.failureStage)
                        assertEquals(outcome.getValue("status").jsonPrimitive.int, failure.status, name)
                        assertEquals(
                            outcome.getValue("detail").jsonObject.getValue("code").jsonPrimitive.content,
                            failure.code, name,
                        )
                        assertEquals(0, work.read { items.count(userId, set.id) }, name)
                    }
                }
            }
        }
    }
}
