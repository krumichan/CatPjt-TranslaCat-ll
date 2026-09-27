package jp.co.translacat.languagelearning.shared.ai

import jp.co.translacat.languagelearning.features.listening.application.ListeningGenerationExecution
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningDuration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListeningSpeechHttpIntegrationTest {
    @Test
    fun `실제 Python 범용 HTTP 경계로 Listening 생성 TTS 정규화 STT와 evidence를 실행한다`() = runBlocking {
        // 준비: 명시된 loopback 합성 서버만 연결하며 실 Provider용 설정을 읽지 않는다.
        val url = checkNotNull(System.getenv("LL_TEST_AI_URL"))
        require(URI(url).host in setOf("127.0.0.1", "localhost", "::1"))
        val key = "synthetic-local-model-key"
        val fixture = Json.parseToJsonElement(
            checkNotNull(javaClass.getResourceAsStream("/contracts/listening-generation-python-golden.json"))
                .bufferedReader().use { it.readText() },
        ).jsonObject
        val deadline = Instant.now().plusSeconds(30)
        HttpModelExecution(url, key).use { model ->
            HttpSpeechExecution(url, key).use { speech ->
                HttpSpeechTranscription(url, key).use { stt ->
                    // 실행: 실제 provider/schema/parser 및 binary HTTP 전달 경로를 모두 통과한다.
                    val items =
                        ListeningGenerationExecution(model).generate(fixture.getValue("request").jsonObject, deadline)
                    val audio = speech.synthesize(
                        SpeechSynthesisCommand(
                            items.first().getValue("sourceText").jsonPrimitive.content,
                            "marin", "ja", "NORMAL", deadline, "synthetic-listening-tts",
                        ),
                    )
                    val normalized =
                        stt.normalize(AudioDecodeCommand(audio.audioBytes, "synthetic-listening-normalize", deadline))
                    val result = stt.transcribe(
                        SpeechTranscriptionCommand(
                            normalized.audioBytes, "synthetic-listening-stt", deadline,
                            "shared", null, "Synthetic hint", 1, true, 500, false,
                        ),
                    )
                    val hasSpeech = stt.hasSpeech(
                        AudioDecodeCommand(normalized.audioBytes, "synthetic-listening-evidence", deadline),
                    )

                    // 검증: 합성 모델 결과이며 실제 모델 품질 성공으로 해석하지 않는다.
                    assertEquals(2, items.size)
                    assertEquals(10.0, ListeningDuration.measuredSeconds(audio.audioBytes, audio.contentType))
                    assertEquals(10.0, normalized.durationSeconds)
                    assertEquals(16000, normalized.sampleRate)
                    assertEquals(1, normalized.channels)
                    assertEquals("Synthetic transcript", result.text)
                    assertEquals("test-provider", result.provider)
                    assertTrue(hasSpeech)
                }
            }
        }
    }
}
