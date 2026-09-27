package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import jp.co.translacat.languagelearning.features.listening.application.ListeningEvaluationExecution
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionCommand
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionPort
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class LevelListeningEvaluationExecutionTest {
    @Test
    fun `Python Level Test 받아쓰기와 해석 결과 및 프롬프트를 보존한다`() = runBlocking {
        // 준비: 기존 Python 서비스에서 출력한 PRACTICE 원본 평가와 Level Test 매핑이다.
        val now = LocalDateTime.of(2026, 9, 26, 4, 0)
        val cases = Json.parseToJsonElement(
            checkNotNull(
                javaClass.getResource(
                    "/contracts/leveltest-listening-python-golden.json",
                ),
            ).readText(),
        ).jsonArray
        for (element in cases) {
            val fixture = element.jsonObject
            val request = fixture.getValue("request").jsonObject
            val type = LevelTestItemType.valueOf(fixture.getValue("itemType").jsonPrimitive.content)
            val session = LevelSession(
                id = 1, uid = "synthetic", userId = 123,
                sessionType = LevelTestSessionType.INITIAL, originLanguage = "ko", learningLanguage = "en",
                timezone = "Asia/Seoul", startedAt = now, lastActivityAt = now, idempotencyKey = "synthetic",
            )
            val item = LevelItem(
                id = 2, sessionId = 1, questionNumber = 13, createdAt = now,
                data = LevelQuestionData(
                    "synthetic", 1, 13, 20, LevelTestDomain.LISTENING,
                    type, 3, "합성 안내", "ko", LevelTestAnswerMode.TEXT, "en", "합성 안내",
                    referencePayload = JsonObject(
                        request.filterKeys {
                            it in setOf("sourceText", "referenceMeanings", "keyMeaningUnits")
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
                textAnswer = request.getValue("answer").jsonPrimitive.content, submittedAt = now,
            )
            val commands = mutableListOf<ModelExecutionCommand>()
            val model = ModelExecutionPort { command ->
                commands += command
                ModelExecutionResult(fixture.getValue("providerOutput"), 0, 0, "synthetic", "synthetic")
            }

            // 실행: 받아쓰기는 로컬 정책, 해석은 범용 모델 경계에서 같은 출력으로 평가한다.
            val result = LevelListeningEvaluationExecution(ListeningEvaluationExecution(model))
                .evaluate(session, item, submission, Instant.now().plusSeconds(180))

            // 검증: metric 근거·상세 피드백·추천 답변과 공식 성장용 signal 형식을 보존한다.
            assertEquals(Json.decodeFromJsonElement<LevelEvaluationData>(fixture.getValue("response")), result)
            if (type == LevelTestItemType.LISTENING_DICTATION) assertEquals(0, commands.size)
            else {
                assertEquals(1, commands.size)
                assertEquals(
                    fixture.getValue("prompt").jsonPrimitive.content, commands.single().messages.single().content,
                )
            }
        }
    }
}
