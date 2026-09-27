package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import jp.co.translacat.languagelearning.shared.ai.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.*

class LevelSpeakingEvaluationExecutionTest {
    private val now = Instant.parse("2026-09-26T04:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val dateTime = LocalDateTime.ofInstant(now, ZoneOffset.UTC)
    private val cases = Json.parseToJsonElement(
        checkNotNull(
            javaClass.getResource(
                "/contracts/leveltest-speaking-python-golden.json",
            ),
        ).readText(),
    ).jsonArray

    @Test
    fun `Python Speaking 원본 평가의 프롬프트 정규화 피드백 점수를 보존한다`() = runBlocking {
        // 준비: 실제 Python 서비스의 합성 전사·모델 결과로 만든 golden이다.
        for (element in cases) {
            val fixture = element.jsonObject
            val context = context(fixture)
            val speech = SpeechDouble()
            val commands = mutableListOf<ModelExecutionCommand>()
            val execution = LevelSpeakingEvaluationExecution(
                speech,
                ModelExecutionPort { command ->
                    commands += command
                    ModelExecutionResult(fixture.getValue("providerOutput"), 17, 11, "synthetic", "synthetic")
                },
                clock,
            )

            // 실행
            val result =
                execution.evaluate(context.first, context.second, submission(), byteArrayOf(1), now.plusSeconds(180))

            // 검증: 반복·유도·비응답 상한을 포함한 기존 결과 전체와 모델 프롬프트가 같다.
            assertEquals(Json.decodeFromJsonElement<LevelEvaluationData>(fixture.getValue("response")), result)
            assertEquals(
                fixture.getValue("canonical"),
                LevelSpeakingEvaluationPolicy.parse(
                    fixture.getValue("providerOutput"), context.second.data.itemType, "ko",
                ).payload,
            )
            assertEquals(fixture.getValue("prompt").jsonPrimitive.content, commands.single().messages.single().content)
            assertEquals(ModelTier.MINI, commands.single().tier)
            assertEquals(now.plusSeconds(60), commands.single().deadlineUtc)
            assertEquals(1, speech.commands.size)
            assertEquals("accurate", speech.commands.single().runtime)
            assertEquals(1, speech.commands.single().beamSize)
        }
    }

    @Test
    fun `발화 증거가 있을 때만 VAD 없이 한 번 재전사한다`() = runBlocking {
        // 준비
        val fixture = cases.first().jsonObject
        val (session, item) = context(fixture)
        for (evidence in listOf(false, true)) {
            val speech = SpeechDouble(blankFirst = true, evidence = evidence)
            var calls = 0
            val execution = LevelSpeakingEvaluationExecution(
                speech,
                ModelExecutionPort {
                    calls++
                    ModelExecutionResult(fixture.getValue("providerOutput"), 0, 0, "synthetic", "synthetic")
                },
                clock,
            )

            // 실행
            val result = execution.evaluate(session, item, submission(), byteArrayOf(1), now.plusSeconds(180))

            // 검증
            assertEquals(if (evidence) 2 else 1, speech.commands.size)
            assertTrue(speech.commands.first().vadFilter)
            assertEquals(if (evidence) 1 else 0, calls)
            if (evidence) assertFalse(speech.commands.last().vadFilter)
            else assertEquals("INVALID_AUDIO", result.reasonCode)
        }
    }

    @Test
    fun `무음과 낮은 전사 confidence는 모델 평가 없이 기존 평가 불가로 반환한다`() = runBlocking {
        // 준비
        val (session, item) = context(cases.first().jsonObject)
        for ((rms, confidence, expected) in listOf(
            Triple(0.0029996, -0.2, "SILENCE_DETECTED"),
            Triple(0.1, -2.0, "LOW_STT_CONFIDENCE"),
        )) {
            val execution = LevelSpeakingEvaluationExecution(
                SpeechDouble(rms = rms, logprob = confidence),
                ModelExecutionPort { error("평가를 호출하면 안 됩니다.") }, clock,
            )

            // 실행
            val result = execution.evaluate(session, item, submission(), byteArrayOf(1), now.plusSeconds(180))

            // 검증
            assertFalse(result.evaluable)
            assertEquals(expected, result.reasonCode)
            assertNull(result.score)
        }
    }

    @Test
    fun `전사 프로토콜 결함은 학습자의 평가 불가로 삼키지 않는다`() = runBlocking {
        // 준비
        val (session, item) = context(cases.first().jsonObject)
        val speech = object : SpeechTranscriptionPort {
            override suspend fun normalize(command: AudioDecodeCommand): AudioDecodeResult =
                throw ModelExecutionFailure("SPEECH_EXECUTION_PROTOCOL", 502, false)

            override suspend fun transcribe(command: SpeechTranscriptionCommand): SpeechTranscriptionResult =
                error("호출되면 안 됩니다.")
        }
        val execution = LevelSpeakingEvaluationExecution(speech, ModelExecutionPort { error("호출되면 안 됩니다.") }, clock)

        // 실행·검증
        val failure = assertFailsWith<ModelExecutionFailure> {
            execution.evaluate(session, item, submission(), byteArrayOf(1), now.plusSeconds(180))
        }
        assertEquals("SPEECH_EXECUTION_PROTOCOL", failure.code)
    }

    @Test
    fun `비응답의 잘못된 과제 점수와 중복 지표는 합법적인 거부와 구분한다`() {
        // 준비
        val raw = cases.last().jsonObject.getValue("providerOutput").jsonObject
        val badTask = raw.getValue("metrics").jsonArray.map { element ->
            val metric = element.jsonObject
            if (metric["type"] == JsonPrimitive("taskfulfillment")) JsonObject(
                metric + ("score" to JsonPrimitive(0.5)),
            ) else metric
        }
        val duplicated = raw.getValue("metrics").jsonArray.toMutableList().apply { set(4, first()) }

        // 실행·검증: 거부 응답도 원본 교차 필드 검증을 반드시 통과해야 한다.
        for (metrics in listOf(badTask, duplicated)) assertFailsWith<LevelSpeakingProtocolFailure> {
            LevelSpeakingEvaluationPolicy.parse(
                JsonObject(raw + ("metrics" to JsonArray(metrics))),
                LevelTestItemType.SPEAKING_SHORT_RESPONSE, "ko",
            )
        }
    }

    private fun context(fixture: JsonObject): Pair<LevelSession, LevelItem> {
        val request = fixture.getValue("request").jsonObject
        val session = LevelSession(
            id = 1, uid = "synthetic", userId = 123, sessionType = LevelTestSessionType.INITIAL,
            originLanguage = "ko", learningLanguage = "en", timezone = "Asia/Seoul", startedAt = dateTime,
            lastActivityAt = dateTime, idempotencyKey = "synthetic",
        )
        val item = LevelItem(
            2, 1, 19,
            LevelQuestionData(
                "synthetic", 1, 19, 20, LevelTestDomain.SPEAKING,
                LevelTestItemType.valueOf(fixture.getValue("itemType").jsonPrimitive.content), 3, "합성 안내", "ko",
                LevelTestAnswerMode.AUDIO, "en", request.getValue("promptText").jsonPrimitive.content,
                maxAudioSeconds = 20,
                referencePayload = JsonObject(
                    request.filterKeys {
                        it in setOf(
                            "referenceText", "phraseHints", "providedFacts", "requiredIntents", "responseConstraints",
                        )
                    },
                ),
                diversityMetadata = LevelDiversityMetadata(
                    "WORK", contentHash = "synthetic", similarityKey = "synthetic",
                ),
                generationVersion = "level-test-generation",
            ),
            createdAt = dateTime,
        )
        return session to item
    }

    private fun submission() =
        LevelSubmission(itemId = 2, idempotencyKey = "synthetic", fingerprint = "synthetic", submittedAt = dateTime)

    private class SpeechDouble(
        val blankFirst: Boolean = false, val evidence: Boolean = false,
        val rms: Double = 0.1, val logprob: Double = -0.2,
    ) : SpeechTranscriptionPort {
        val commands = mutableListOf<SpeechTranscriptionCommand>()
        override suspend fun normalize(command: AudioDecodeCommand) =
            AudioDecodeResult(byteArrayOf(1), 2.0, "wav", rms, 0.2, 0.0, 16000, 1)

        override suspend fun hasSpeech(command: AudioDecodeCommand) = evidence
        override suspend fun transcribe(command: SpeechTranscriptionCommand): SpeechTranscriptionResult {
            commands += command
            val text = if (blankFirst && commands.size == 1) "" else " Synthetic spoken answer "
            return SpeechTranscriptionResult(
                text, "en", 0.9, 2.0,
                listOf(SpeechTranscriptionSegment(0.0, 2.0, text, logprob, null)), "synthetic-stt", "synthetic-stt",
                "synthetic-v1",
            )
        }
    }
}
