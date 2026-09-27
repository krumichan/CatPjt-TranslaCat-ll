package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import jp.co.translacat.languagelearning.features.writing.application.WritingEvaluationExecution
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionCommand
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionPort
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionResult
import jp.co.translacat.languagelearning.shared.ai.ModelTier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LevelWritingEvaluationExecutionTest {
    private val now = Instant.parse("2026-09-26T04:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val dateTime = LocalDateTime.ofInstant(now, ZoneOffset.UTC)

    @Test
    fun `Python Writing 공유 평가와 Level Test 피드백 계약을 그대로 보존한다`() = runBlocking {
        // 준비: 실제 Python LevelTestService와 WritingService를 합성 Provider로 실행한 golden이다.
        val cases = Json.parseToJsonElement(
            checkNotNull(
                javaClass.getResource(
                    "/contracts/leveltest-writing-python-golden.json",
                ),
            ).readText(),
        ).jsonArray
        for (element in cases) {
            val fixture = element.jsonObject
            val request = fixture.getValue("request").jsonObject
            val session = LevelSession(
                id = 1, uid = "synthetic", userId = 123,
                sessionType = LevelTestSessionType.INITIAL, originLanguage = "ko", learningLanguage = "en",
                timezone = "Asia/Seoul", startedAt = dateTime, lastActivityAt = dateTime, idempotencyKey = "synthetic",
            )
            val item = LevelItem(
                id = 2, sessionId = 1, questionNumber = 15, createdAt = dateTime,
                data = LevelQuestionData(
                    "synthetic", 1, 15, 20, LevelTestDomain.WRITING,
                    LevelTestItemType.valueOf(fixture.getValue("itemType").jsonPrimitive.content), 3,
                    "합성 안내", "ko", LevelTestAnswerMode.TEXT, "en",
                    request.getValue("promptText").jsonPrimitive.content,
                    referencePayload = JsonObject(
                        request.filterKeys {
                            it in setOf(
                                "translationSourceText", "providedFacts", "requiredIntents", "responseConstraints",
                            )
                        },
                    ),
                    diversityMetadata = LevelDiversityMetadata(
                        "WORK", contentHash = "synthetic", similarityKey = "synthetic",
                    ),
                    generationVersion = "level-test-generation",
                ),
            )
            val submission = LevelSubmission(
                itemId = 2, idempotencyKey = "synthetic", fingerprint = "synthetic",
                textAnswer = "Synthetic answer", submittedAt = dateTime,
            )
            val commands = mutableListOf<ModelExecutionCommand>()
            val model = ModelExecutionPort { command ->
                commands += command
                ModelExecutionResult(fixture.getValue("providerOutput"), 0, 0, "synthetic", "synthetic")
            }

            // 실행: 새 범용 실행 경계에서 같은 모델 출력만 고정한다.
            val result = LevelWritingEvaluationExecution(WritingEvaluationExecution(model), clock)
                .evaluate(session, item, submission, now.plusSeconds(180))

            // 검증: 프롬프트·반올림·원문 반복 제외·피드백 상세·성장용 signal 의미가 같다.
            val expected = Json.decodeFromJsonElement<LevelEvaluationData>(fixture.getValue("response"))
            assertEquals(expected, result)
            assertEquals(1, commands.size)
            assertEquals(fixture.getValue("prompt").jsonPrimitive.content, commands.single().messages.single().content)
            assertEquals(ModelTier.MINI, commands.single().tier)
            assertEquals(now.plusSeconds(30), commands.single().deadlineUtc)
            assertTrue(result.strengths.none { it.contains("Straße") })
        }
    }
}
