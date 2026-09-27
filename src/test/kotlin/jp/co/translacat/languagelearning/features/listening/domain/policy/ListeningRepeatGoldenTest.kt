package jp.co.translacat.languagelearning.features.listening.domain.policy

import jp.co.translacat.languagelearning.features.listening.application.ListeningRepeatExecution
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningEvaluationContext
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskResult
import jp.co.translacat.languagelearning.shared.ai.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ListeningRepeatGoldenTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `Python Repeat 정상 거부 연습 원본 9경로의 점수와 안내문을 보존한다`() = runTest {
        // 준비: 원본 Python 서비스 실행 결과와 기술 Provider 출력만 고정한다.
        val cases = Json.parseToJsonElement(
            checkNotNull(
                javaClass.getResourceAsStream(
                    "/contracts/listening-repeat-python-golden.json",
                ),
            ).bufferedReader().use { it.readText() },
        ).jsonArray
        assertEquals(9, cases.size)
        for (entry in cases) {
            val case = entry.jsonObject
            val request = case.getValue("request").jsonObject
            val quality = case.getValue("quality").jsonObject
            val transcript = case.getValue("transcript").jsonObject
            var calls = 0
            val port = object : SpeechTranscriptionPort {
                override suspend fun normalize(command: AudioDecodeCommand) = AudioDecodeResult(
                    Base64.getDecoder().decode(case.text("audioBase64")), case.number("duration"), "wav",
                    quality.number("rms"), quality.number("peak"), quality.number("silenceRatio"), 16000, 1,
                )

                override suspend fun transcribe(command: SpeechTranscriptionCommand): SpeechTranscriptionResult {
                    calls++
                    return SpeechTranscriptionResult(
                        transcript.text("text"), transcript.text("language"),
                        transcript.number("language_probability"), null,
                        transcript.getValue("segments").jsonArray.map { value ->
                            val segment = value.jsonObject
                            SpeechTranscriptionSegment(
                                segment.number("start_seconds"), segment.number("end_seconds"),
                                segment.text("text"), segment.number("avg_logprob"), null,
                            )
                        },
                        "test-provider", "synthetic", "synthetic",
                    )
                }
            }

            // 실행
            val actual = ListeningRepeatExecution(port).evaluate(
                byteArrayOf(1), request.text("sourceText"),
                request.number("sourceDurationSeconds"), request.text("learningLanguage"), listOf("hello", "world"),
                "synthetic-repeat", Instant.now().plusSeconds(30),
                ListeningEvaluationContext(
                    request.text("evaluationPurpose") == "OFFICIAL",
                    request.getValue("answerRevealed").jsonPrimitive.boolean,
                ),
                0,
            )
            val expected = json.decodeFromJsonElement<ListeningTaskResult>(case.getValue("result"))

            // 검증: 진단 전용 metadata를 제외한 실제 결과 계약 전체와 전사 실행 수가 같다.
            assertEquals(expected, actual, case.text("name"))
            assertEquals(case.getValue("sttCalls").jsonPrimitive.int, calls, case.text("name"))
        }
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.double
}
