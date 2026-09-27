package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelTestRules
import jp.co.translacat.languagelearning.features.leveltest.infrastructure.ai.HttpLevelTestAi
import jp.co.translacat.languagelearning.shared.ai.HttpModelExecution
import jp.co.translacat.languagelearning.shared.ai.HttpSpeechExecution
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 명시적인 aiHttpIntegrationTest에서 실제 Python 범용 실행 HTTP를 통과한다. */
class LevelEvaluationHttpIntegrationTest {
    private val date = LocalDateTime.of(2026, 9, 26, 4, 0)
    private val session = LevelSession(
        id = 1, uid = "synthetic", userId = 123,
        sessionType = LevelTestSessionType.INITIAL, originLanguage = "ko", learningLanguage = "en",
        timezone = "Asia/Seoul", startedAt = date, lastActivityAt = date, idempotencyKey = "synthetic",
    )

    @Test
    fun `전체 20슬롯 생성과 독립 검증을 실제 Python 범용 HTTP로 실행한다`() = runBlocking {
        // 준비: 생성·설계·검증의 모델 출력만 합성이며 Kotlin 업무 루프와 범용 HTTP는 실제 구현이다.
        val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
        require(URI(url).host in setOf("127.0.0.1", "localhost", "::1"))
        HttpModelExecution(url, "synthetic-local-model-key").use { model ->
            HttpSpeechExecution(url, "synthetic-local-model-key").use { speech ->
                val uploaded = mutableMapOf<String, ByteArray>()
                val execution = LevelGenerationExecution(
                    model, speech,
                    { upload, bytes, mime ->
                        assertEquals("audio/wav", mime)
                        assertTrue(bytes.size > 44)
                        uploaded[upload.objectKey] = bytes
                    },
                )
                for (element in fixtures("generation-execution")) {
                    val fixture = element.jsonObject
                    if (!fixture.getValue("name").jsonPrimitive.content.endsWith("_normal")) continue
                    val request = fixture.getValue("request").jsonObject
                    val number = request.getValue("questionNumber").jsonPrimitive.int
                    val upload = if (LevelTestRules.requiresAudio(LevelTestRules.slot(number).itemType))
                        LevelAudioUpload("http://127.0.0.1/synthetic-local-upload", "synthetic-$number.wav") else null

                    // 실행
                    val result = execution.generate(request, upload, Instant.now().plusSeconds(180))

                    // 검증: 실제 시간과 TTS 참조를 제외한 출제 결과·부분점수·다양성·token 수는 원본과 일치한다.
                    val expected = Json.decodeFromJsonElement<LevelQuestionData>(fixture.getValue("response"))
                    val expectedUsage = JsonObject(
                        checkNotNull(expected.usage) + ("latencyMs" to checkNotNull(result.usage)["latencyMs"]!!),
                    )
                    assertEquals(
                        expected.copy(usage = expectedUsage, referenceAudio = result.referenceAudio), result,
                        "q$number",
                    )
                    if (upload != null) {
                        assertEquals(upload.objectKey, result.referenceAudio?.objectKey)
                        assertEquals(
                            LevelTestRules.sha256(uploaded.getValue(upload.objectKey)),
                            result.referenceAudio?.checksumSha256,
                        )
                    }
                }
                assertEquals(6, uploaded.size)
            }
        }
    }

    @Test
    fun `Level Test Writing와 Listening 평가를 실제 Python 범용 HTTP로 실행한다`() = runBlocking {
        // 준비: 실제 HttpLevelTestAi와 기록된 Python 합성 모델 출력만 사용한다.
        adapter().use { ai ->
            for (domain in listOf("writing", "listening")) for (element in fixtures(domain)) {
                val fixture = element.jsonObject
                val request = fixture.getValue("request").jsonObject
                val item = item(request, domain.uppercase())
                val answer = request["answer"]?.jsonPrimitive?.content ?: "Synthetic answer"

                // 실행
                val result = ai.evaluate(session, item, submission(answer), null)

                // 검증: 업무 평가 결과는 Python 원본 golden과 같다.
                assertEquals(Json.decodeFromJsonElement<LevelEvaluationData>(fixture.getValue("response")), result)
            }
        }
    }

    @Test
    fun `Level Test Speaking은 실제 TTS 정규화 전사와 모델 HTTP를 연결한다`() = runBlocking {
        // 준비
        adapter().use { ai ->
            val policy = LevelTestContext(
                "ko", "en", "Asia/Seoul", true, 0, false,
                "level-test-multiskill", "level-test-model-config", 2,
            )
            for (element in fixtures("speaking")) {
                val fixture = element.jsonObject
                val request = fixture.getValue("request").jsonObject
                val item = item(request, "SPEAKING")

                // 실행: 합성 기술 Provider의 오디오가 실제 HTTP로 반환된 뒤 같은 음성 입력으로 평가한다.
                val audio = ai.synthesize(session, item, "Synthetic reference", policy)
                val result = ai.evaluate(session, item, submission(null), audio.bytes)

                // 검증: 실제 전사와 사용량을 제외한 점수·정규화·피드백은 원본과 완전히 같다.
                assertEquals("audio/wav", audio.contentType)
                assertTrue(audio.bytes.size > 44)
                val expected = Json.decodeFromJsonElement<LevelEvaluationData>(fixture.getValue("response"))
                assertEquals(expected.copy(transcript = "Synthetic transcript", usage = result.usage), result)
                assertEquals("test-provider", result.usage?.get("provider")?.jsonPrimitive?.content)
                assertEquals("synthetic-leveltest", result.usage?.get("model")?.jsonPrimitive?.content)
                assertEquals(17, result.usage?.get("inputTokens")?.jsonPrimitive?.int)
                assertEquals(11, result.usage?.get("outputTokens")?.jsonPrimitive?.int)
            }
        }
    }

    @Test
    fun `실제 범용 HTTP의 알 수 없는 모델 입력 실패를 성공 평가로 바꾸지 않는다`() = runBlocking {
        // 준비
        val request = fixtures("writing").first().jsonObject.getValue("request").jsonObject

        // 실행·검증
        adapter().use { ai ->
            val failure = assertFailsWith<LevelTestException> {
                ai.evaluate(session, item(request, "WRITING"), submission("Unknown synthetic answer"), null)
            }
            assertEquals(502, failure.httpStatus)
        }
    }

    private fun adapter(): HttpLevelTestAi {
        val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
        require(URI(url).host in setOf("127.0.0.1", "localhost", "::1"))
        return HttpLevelTestAi(url, "synthetic-local-model-key", 30)
    }

    private fun fixtures(name: String) = Json.parseToJsonElement(
        checkNotNull(
            javaClass.getResource(
                "/contracts/leveltest-$name-python-golden.json",
            ),
        ).readText(),
    ).jsonArray

    private fun submission(answer: String?) = LevelSubmission(
        itemId = 2, idempotencyKey = "synthetic",
        fingerprint = "synthetic", textAnswer = answer, submittedAt = date, audioContentType = "audio/wav",
    )

    private fun item(request: JsonObject, domain: String): LevelItem {
        val speaking = domain == "SPEAKING"
        return LevelItem(
            id = 2, sessionId = 1, questionNumber = if (speaking) 19 else 15, createdAt = date,
            data = LevelQuestionData(
                "synthetic", 1, if (speaking) 19 else 15, 20, LevelTestDomain.valueOf(domain),
                LevelTestItemType.valueOf(request.getValue("itemType").jsonPrimitive.content), 3, "합성 안내", "ko",
                if (speaking) LevelTestAnswerMode.AUDIO else LevelTestAnswerMode.TEXT, "en",
                request.getValue("promptText").jsonPrimitive.content,
                maxAudioSeconds = if (speaking) 20 else null,
                referencePayload = JsonObject(
                    request.filterKeys {
                        it in setOf(
                            "translationSourceText", "sourceText",
                            "referenceMeanings", "keyMeaningUnits", "referenceText", "phraseHints", "providedFacts",
                            "requiredIntents", "responseConstraints",
                        )
                    },
                ),
                diversityMetadata = LevelDiversityMetadata(
                    "WORK", contentHash = "synthetic", similarityKey = "synthetic",
                ),
                generationVersion = "level-test-generation",
            ),
        )
    }
}
