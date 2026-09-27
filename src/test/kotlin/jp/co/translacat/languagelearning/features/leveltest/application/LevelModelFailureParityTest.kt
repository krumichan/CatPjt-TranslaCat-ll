package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import jp.co.translacat.languagelearning.features.writing.application.WritingEvaluationExecution
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionFailure
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionPort
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionResult
import jp.co.translacat.languagelearning.shared.ai.SpeechExecutionPort
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LevelModelFailureParityTest {
    private val now = Instant.parse("2026-09-26T04:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `생성 choice task의 실제 Python 오류30경로 호출수와 최종status를 보존한다`() = runBlocking {
        // 준비
        for (fixture in resource("leveltest-provider-failure").jsonArray.map { it.jsonObject }) {
            val stage = "LANGUAGE_LEARNING_LEVEL_TEST_" + fixture.getValue("stage").jsonPrimitive.content
            val normal = fixture.getValue("normalCalls").jsonArray.map { it.jsonObject }
            val calls = mutableListOf<String>()
            val model = ModelExecutionPort { command ->
                calls += checkNotNull(command.taskName)
                if (command.taskName == stage) throw ModelExecutionFailure(
                    fixture.getValue("code").jsonPrimitive.content,
                    fixture.getValue("status").jsonPrimitive.int, fixture.getValue("retryable").jsonPrimitive.boolean,
                    failureKind = fixture.getValue("failureKind").jsonPrimitive.contentOrNull,
                    providerStatus = fixture.getValue("providerStatus").jsonPrimitive.intOrNull,
                )
                ModelExecutionResult(
                    normal.first { it["type"] == JsonPrimitive(command.taskName) }.getValue("output"),
                    7, 4, "synthetic", "synthetic",
                )
            }
            val execution = LevelGenerationExecution(
                model, SpeechExecutionPort { error("음성 호출 없음") },
                { _, _, _ -> error("업로드 없음") }, clock,
            )

            // 실행
            val failure = assertFailsWith<LevelTestException> {
                execution.generate(fixture.getValue("request").jsonObject, null, now.plusSeconds(180))
            }

            // 검증: 거부는 품질 후보로 변환하지 않고 원본의 제한된 Provider 재시도만 보존한다.
            val label = fixture.getValue("name").jsonPrimitive.content + ":" + stage
            assertEquals(fixture.getValue("expectedHttpStatus").jsonPrimitive.int, failure.httpStatus, label)
            assertEquals(fixture.getValue("calls").jsonArray.map { it.jsonPrimitive.content }, calls, label)
            assertEquals(fixture.getValue("code").jsonPrimitive.content, failure.code, label)
        }
    }

    @Test
    fun `Level Writing 평가의 원본 일반오류 2회와 새 protocol 즉시실패를 구분한다`() = runBlocking {
        // 준비: 퇴역 Writing 원본 메서드 실행 golden을 사용하고 정상 Level 문항 계약을 구성한다.
        val date = LocalDateTime.ofInstant(now, ZoneOffset.UTC)
        val session = LevelSession(
            1, "synthetic", 123, LevelTestSessionType.INITIAL, originLanguage = "ko",
            learningLanguage = "en", timezone = "Asia/Seoul", startedAt = date, lastActivityAt = date,
            idempotencyKey = "synthetic",
        )
        val item = LevelItem(
            2, 1, 15,
            LevelQuestionData(
                "synthetic", 1, 15, 20, LevelTestDomain.WRITING,
                LevelTestItemType.WRITING_TRANSLATION, 3, "Translate.", "en", LevelTestAnswerMode.TEXT, "en",
                "합성 원문입니다.",
                referencePayload = buildJsonObject { put("translationSourceText", "합성 원문입니다.") },
                diversityMetadata = LevelDiversityMetadata(
                    "WORK", contentHash = "synthetic", similarityKey = "synthetic",
                ),
                generationVersion = "level-test-generation",
            ),
            createdAt = date,
        )
        val answer = LevelSubmission(
            itemId = 2, idempotencyKey = "synthetic", fingerprint = "synthetic", textAnswer = "Synthetic answer",
            submittedAt = date,
        )
        val cases = resource("writing-evaluation-failure").jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
            .filter { it["name"] != JsonPrimitive("http_exception") }
        for (fixture in cases) {
            var calls = 0
            val model = ModelExecutionPort {
                calls++
                when (fixture.getValue("name").jsonPrimitive.content) {
                    "invalid_schema" -> ModelExecutionResult(JsonObject(emptyMap()), 0, 0, "synthetic", "synthetic")
                    "non_object" -> ModelExecutionResult(JsonArray(emptyList()), 0, 0, "synthetic", "synthetic")
                    else -> throw ModelExecutionFailure(
                        fixture.getValue("code").jsonPrimitive.content, 502, false,
                        failureKind = fixture.getValue("failureKind").jsonPrimitive.contentOrNull,
                        providerStatus = fixture.getValue("providerStatus").jsonPrimitive.intOrNull,
                    )
                }
            }

            // 실행·검증
            val failure = assertFailsWith<LevelTestException> {
                LevelWritingEvaluationExecution(WritingEvaluationExecution(model), clock).evaluate(
                    session, item, answer, now.plusSeconds(180),
                )
            }
            assertEquals(fixture.getValue("calls").jsonPrimitive.int, calls, fixture["name"].toString())
            assertEquals(
                fixture.getValue("httpStatus").jsonPrimitive.int, failure.httpStatus, fixture["name"].toString(),
            )
        }
        var protocolCalls = 0
        val invalid =
            ModelExecutionPort { protocolCalls++; throw ModelExecutionFailure("MODEL_EXECUTION_PROTOCOL", 502, false) }
        assertFailsWith<LevelTestException> {
            LevelWritingEvaluationExecution(WritingEvaluationExecution(invalid), clock).evaluate(
                session, item, answer, now.plusSeconds(180),
            )
        }
        assertEquals(1, protocolCalls)
    }

    private fun resource(name: String) = Json.parseToJsonElement(
        checkNotNull(
            javaClass.getResource(
                "/contracts/$name-python-golden.json",
            ),
        ).readText(),
    )
}
