package jp.co.translacat.languagelearning.shared.ai

import jp.co.translacat.languagelearning.features.writing.application.WritingEvaluationExecution
import jp.co.translacat.languagelearning.features.writing.application.WritingGenerationExecution
import jp.co.translacat.languagelearning.features.writing.application.WritingNoteLocalizationExecution
import jp.co.translacat.languagelearning.features.writing.application.WritingReviewExecution
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 별도 aiHttpIntegrationTest에서만 실행한다. 실제 FastAPI route/auth/provider adapter를 통과한다. */
class ModelExecutionHttpIntegrationTest {
    @Test
    fun `LL note 제안은 Python 범용 HTTP 뒤의 별도 Mini 검사 없이는 승인하지 않는다`() {
        val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
        val golden = Json.parseToJsonElement(
            requireNotNull(
                javaClass.getResource(
                    "/contracts/writing-note-python-golden.json",
                ),
            ).readText(),
        ).jsonObject
        val request = golden.getValue("request").jsonObject
        val draft = WritingCandidatePolicy.parse(golden.getValue("draft").jsonObject)
        HttpModelExecution(url, "synthetic-local-model-key").use { model ->
            val result = runBlocking {
                WritingNoteLocalizationExecution(model).localize(
                    request, draft, golden.getValue("candidateId").jsonPrimitive.content,
                    3, Instant.now().plusSeconds(15),
                    golden.getValue("reviewId").jsonPrimitive.content,
                )
            }
            assertEquals("VERIFIED_NOTE_LOCALIZED", result.reason)
            assertEquals(
                golden.getValue("localized").jsonObject.getValue("focusReason").jsonPrimitive.content,
                result.draft?.focusReason,
            )
            assertEquals(draft.originText, result.draft?.originText)
        }
    }

    @Test
    fun `TARGETED_REPAIR 수정본은 실제 HTTP에서 보존 교차 필드를 재검증하고 B4를 승격하지 않는다`() {
        val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
        val golden = Json.parseToJsonElement(
            requireNotNull(
                javaClass.getResource(
                    "/contracts/writing-repair-python-golden.json",
                ),
            ).readText(),
        ).jsonObject
        val request = golden.getValue("request").jsonObject
        val original = WritingCandidatePolicy.parse(golden.getValue("original").jsonObject)
        val revised = WritingCandidatePolicy.parse(golden.getValue("revised").jsonObject)
        val plan = WritingDifficultyRepairPlan(
            original, golden.getValue("baseContentHash").jsonPrimitive.content, 1, WritingType.FREE,
            listOf(WritingRepairObservation("SCOPE_INTERACTION", listOf("O1"))),
            listOf(WritingRepairObservation("SCOPE_TOO_ROUTINE", listOf("O1"))),
        )
        HttpModelExecution(url, "synthetic-local-model-key").use { client ->
            val repairedBatch = runBlocking {
                WritingGenerationExecution(client).repair(
                    request, plan, Instant.now().plusSeconds(15),
                )
            }
            assertEquals(emptyMap(), repairedBatch.rejections)
            assertEquals(revised, repairedBatch.drafts.single())
            val execution = WritingReviewExecution(client)
            val accepted = runBlocking {
                execution.assess(
                    request, repairedBatch.drafts.single(), "synthetic-repaired", Instant.now().plusSeconds(15), plan,
                )
            }
            assertEquals(
                "ACCEPT",
                WritingReviewAcceptance.decide(
                    accepted.review, 5, WritingType.FREE, repaired = true,
                ).action,
            )
            val invalid = assertFailsWith<WritingReviewProtocolException> {
                runBlocking {
                    execution.assess(
                        request, revised, "synthetic-repaired-malformed", Instant.now().plusSeconds(15), plan,
                    )
                }
            }
            assertEquals("REVISION_STATUS_ISSUE_MISMATCH", invalid.code)
            val b4 = runBlocking {
                execution.assess(
                    request, revised, "synthetic-repaired-b4", Instant.now().plusSeconds(15), plan,
                )
            }
            assertEquals(
                "VERIFIED_BAND_MISMATCH",
                WritingReviewAcceptance.decide(
                    b4.review, 5, WritingType.FREE, repaired = true,
                ).reason,
            )
        }
    }

    @Test
    fun `LL Writing 후보 생성은 실제 Python 실행 HTTP 뒤에서 후보 오류와 Provider 거부를 구분한다`() {
        val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
        val cases = Json.parseToJsonElement(
            requireNotNull(
                javaClass.getResource(
                    "/contracts/writing-candidate-policy-python-golden.json",
                ),
            ).readText(),
        ).jsonArray
        val request = cases.first().jsonObject.getValue("request").jsonObject
        HttpModelExecution(url, "synthetic-local-model-key").use { client ->
            val execution = WritingGenerationExecution(client)
            val batch = runBlocking {
                execution.generate(
                    request, WritingType.FREE, 3, 1, Instant.now().plusSeconds(15),
                )
            }
            assertEquals(1, batch.drafts.size)
            assertEquals(emptyMap(), batch.rejections)
            assertEquals(null, WritingCandidatePolicy.reason(request, WritingType.FREE, 3, batch.drafts.single()))
            val malformed = runBlocking {
                execution.generate(
                    JsonObject(request + ("requestId" to JsonPrimitive("synthetic-generation-malformed"))),
                    WritingType.FREE, 3, 1, Instant.now().plusSeconds(15),
                )
            }
            assertEquals(emptyList(), malformed.drafts)
            assertEquals(mapOf("CANDIDATE_SCHEMA" to 1), malformed.rejections)
            val refusal = assertFailsWith<ModelExecutionFailure> {
                runBlocking {
                    execution.generate(
                        JsonObject(request + ("requestId" to JsonPrimitive("synthetic-generation-refusal"))),
                        WritingType.FREE, 3, 1, Instant.now().plusSeconds(15),
                    )
                }
            }
            assertEquals("REFUSAL", refusal.code)
        }
    }

    @Test
    fun `LL 독립 Writing review가 실제 Python 실행 HTTP와 테스트 Provider 응답을 구분한다`() {
        val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
        val cases = Json.parseToJsonElement(
            requireNotNull(
                javaClass.getResource(
                    "/contracts/writing-candidate-policy-python-golden.json",
                ),
            ).readText(),
        ).jsonArray
        val baseline = cases.first().jsonObject
        val request = baseline.getValue("request").jsonObject
        val draft = WritingCandidatePolicy.parse(baseline.getValue("draft").jsonObject)
        HttpModelExecution(url, "synthetic-local-model-key").use { client ->
            val execution = WritingReviewExecution(client)
            val success = runBlocking {
                execution.assess(
                    request, draft, "synthetic-candidate", Instant.now().plusSeconds(15),
                )
            }
            assertEquals("ACCEPT", WritingReviewAcceptance.decide(success.review, 3, WritingType.FREE).action)
            assertEquals(WritingCandidatePolicy.contentHash(request, draft), success.contentHash)
            val refusal = assertFailsWith<ModelExecutionFailure> {
                runBlocking {
                    execution.assess(
                        request, draft, "synthetic-refusal", Instant.now().plusSeconds(15),
                    )
                }
            }
            assertEquals("REFUSAL", refusal.code)
            assertEquals(
                "SCHEMA_FIELDS_INVALID",
                assertFailsWith<WritingReviewProtocolException> {
                    runBlocking {
                        execution.assess(
                            request, draft, "synthetic-malformed", Instant.now().plusSeconds(15),
                        )
                    }
                }.code,
            )
        }
    }

    @Test
    fun `LL Writing 평가 프롬프트와 Schema가 실제 Python HTTP 테스트 Provider를 통과한다`() {
        val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
        val golden = Json.parseToJsonElement(
            requireNotNull(javaClass.getResource("/contracts/writing-evaluation-python-golden.json")).readText(),
        ).jsonObject
        HttpModelExecution(url, "synthetic-local-model-key").use { client ->
            val evaluated = runBlocking {
                WritingEvaluationExecution(client).evaluate(
                    requestId = "synthetic-writing-eval", context = "DAILY", originLanguage = "ko",
                    learningLanguage = "en",
                    compactRequestJson = golden.getValue("compactPayload").jsonPrimitive.content,
                    deadlineUtc = Instant.now().plusSeconds(15),
                )
            }
            assertEquals(69, evaluated.scores.overall)
            assertEquals(49, evaluated.scores.meaning)
        }
    }

    @Test
    fun `실제 Python HTTP와 테스트 Provider 뒤에서 LL Writing Schema 파서 결합 판정이 실행된다`() {
        val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
        val draft = WritingDraftEvidence("Synthetic origin", emptyList(), emptyList(), emptyList(), "Synthetic focus")
        val command = ModelExecutionCommand(
            traceId = "synthetic-writing-review",
            instructions = "Synthetic instruction",
            messages = listOf(ModelMessage("user", "Synthetic writing review")),
            tier = ModelTier.MINI,
            maxOutputTokens = 2048,
            deadlineUtc = Instant.now().plusSeconds(15),
            responseSchema = WritingReviewSchema.build(draft, "synthetic-candidate", "a".repeat(64)),
            schemaName = "writing_task_review",
            strict = true,
            taskName = "LANGUAGE_LEARNING_WRITING_TASK_VERIFICATION",
        )
        HttpModelExecution(url, "synthetic-local-model-key").use { client ->
            val result = runBlocking { client.execute(command) }
            val review = WritingReviewParser.parse(result.output.jsonObject)
            assertEquals(
                null,
                WritingReviewBinding.failure(
                    review, draft, "synthetic-candidate", "a".repeat(64),
                ),
            )
            assertEquals("ACCEPT", WritingReviewAcceptance.decide(review, 5, WritingType.FREE).action)

            val contradiction = runBlocking {
                client.execute(
                    command.copy(
                        messages = listOf(ModelMessage("user", "Synthetic writing contradiction")),
                        deadlineUtc = Instant.now().plusSeconds(15),
                    ),
                )
            }
            assertEquals(
                "CRITERION_ISSUE_MISMATCH",
                assertFailsWith<WritingReviewProtocolException> {
                    WritingReviewParser.parse(contradiction.output.jsonObject)
                }.code,
            )
        }
    }

    @Test
    fun `LL adapter가 실제 Python 실행 HTTP와 테스트 Provider를 통과한다`() {
        val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
        val command = ModelExecutionCommand(
            traceId = "synthetic-integration-1",
            instructions = "Synthetic instruction",
            messages = listOf(ModelMessage("user", "Synthetic input")),
            tier = ModelTier.MINI,
            maxOutputTokens = 2048,
            deadlineUtc = Instant.now().plusSeconds(15),
            taskName = "LANGUAGE_LEARNING_WRITING_TASK_VERIFICATION",
        )
        HttpModelExecution(url, "synthetic-local-model-key").use { client ->
            val response = runBlocking { client.execute(command) }
            assertEquals("synthetic", response.output.jsonObject.getValue("result").jsonPrimitive.content)
            assertEquals("test-provider", response.provider)
            val refusal = assertFailsWith<ModelExecutionFailure> {
                runBlocking {
                    client.execute(
                        command.copy(
                            messages = listOf(ModelMessage("user", "Synthetic refusal")),
                            deadlineUtc = Instant.now().plusSeconds(15),
                        ),
                    )
                }
            }
            assertEquals("REFUSAL", refusal.code)
            assertEquals(502, refusal.status)
            val schemaFailure = assertFailsWith<ModelExecutionFailure> {
                runBlocking {
                    client.execute(
                        command.copy(
                            messages = listOf(ModelMessage("user", "Synthetic schema")),
                            deadlineUtc = Instant.now().plusSeconds(15),
                        ),
                    )
                }
            }
            assertEquals("EXECUTION_SCHEMA_INVALID", schemaFailure.code)
            assertEquals(422, schemaFailure.status)
            val timeout = assertFailsWith<ModelExecutionFailure> {
                runBlocking {
                    client.execute(
                        command.copy(
                            messages = listOf(ModelMessage("user", "Synthetic timeout")),
                            deadlineUtc = Instant.now().plusMillis(500),
                        ),
                    )
                }
            }
            // Provider와 HTTP client는 동일한 남은 deadline을 받으므로 어느 쪽이 먼저 만료될 수 있다.
            assertTrue(timeout.code in setOf("MODEL_DEADLINE_EXCEEDED", "PROVIDER_TIMEOUT"))
            assertEquals(504, timeout.status)
        }
        HttpModelExecution(url, "invalid-test-key").use { client ->
            val failure = assertFailsWith<ModelExecutionFailure> {
                runBlocking { client.execute(command.copy(deadlineUtc = Instant.now().plusSeconds(15))) }
            }
            assertEquals(401, failure.status)
            assertEquals("MODEL_EXECUTION_UNAUTHORIZED", failure.code)
        }
    }
}
