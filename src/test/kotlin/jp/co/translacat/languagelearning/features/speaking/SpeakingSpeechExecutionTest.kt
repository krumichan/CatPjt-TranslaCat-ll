package jp.co.translacat.languagelearning.features.speaking

import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingSessionPolicySnapshot
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingSpeechExecution
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingStageFailure
import jp.co.translacat.languagelearning.shared.ai.*
import jp.co.translacat.languagelearning.support.SettingsFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class SpeakingSpeechExecutionTest {
    private val policy =
        SpeakingSessionPolicySnapshot.from(SettingsFixtures.admin()).copy(automaticRetryLimitPerStage = 2)

    @Test
    fun `정규화 판정은 반올림 전 값으로 하고 사용량은 원본 정밀도를 따른다`() = runBlocking {
        // 준비: 무음 경계 바로 아래 값은 반올림하면 통과할 수 있으므로 원본 순서를 검사한다.
        var normalized = audio.copy(durationSeconds = 3.1234567, rms = .0029999)
        val source = object : SpeechTranscriptionPort {
            override suspend fun normalize(command: AudioDecodeCommand) = normalized
            override suspend fun transcribe(command: SpeechTranscriptionCommand) = error("전사 호출 없음")
            override suspend fun hasSpeech(command: AudioDecodeCommand) = error("발화 근거 호출 없음")
        }
        val execution = execution(source)

        // 실행
        val failure =
            assertFailsWith<SpeakingStageFailure> { execution.normalize(byteArrayOf(1), "synthetic-round", policy) }
        normalized = normalized.copy(rms = .123456789)
        val accepted = execution.normalize(byteArrayOf(1), "synthetic-round", policy)

        // 검증
        assertEquals("SILENCE_DETECTED", failure.code)
        assertEquals(3.123, accepted.durationSeconds)
        assertEquals(.123457, accepted.rms)
    }

    @Test
    fun `독립 발화 근거가 있을 때만 VAD를 한 번 해제하고 같은 시도의 deadline을 보존한다`() = runBlocking {
        // 준비
        val source = RecordingTranscription(listOf("", " 합성 전사 "), true)
        val execution = execution(source)

        // 실행
        val first = execution.transcribe(audio, "fixture-1", "stt-key", "en", List(23) { "hint$it" }, policy)
        val replay = execution.transcribe(audio, "fixture-1", "stt-key", "en", emptyList(), policy)

        // 검증
        assertEquals(2, source.calls.size)
        assertEquals(1, source.evidenceCalls)
        assertTrue(source.calls[0].vadFilter)
        assertFalse(source.calls[1].vadFilter)
        assertEquals(source.calls[0].deadlineUtc, source.calls[1].deadlineUtc)
        assertEquals(List(20) { "hint$it" }.joinToString(", "), source.calls[0].initialPrompt)
        assertEquals(5, source.calls[0].beamSize)
        assertFalse(source.calls[0].conditionOnPreviousText)
        assertEquals("합성 전사", first.getValue("transcript").jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(first, replay)
    }

    @Test
    fun `발화 근거 없는 빈 전사는 보정하거나 자동 재시도하지 않는다`() = runBlocking {
        // 준비
        val source = RecordingTranscription(listOf(""), false)

        // 실행
        val failure = assertFailsWith<SpeakingStageFailure> {
            execution(source).transcribe(audio, "fixture-empty", "empty-key", "en", emptyList(), policy)
        }

        // 검증
        assertEquals("INVALID_AUDIO", failure.code)
        assertFalse(failure.retryable)
        assertEquals(1, source.calls.size)
        assertEquals(1, source.evidenceCalls)
    }

    @Test
    fun `손상된 완료 WAV는 합성 재시도 없이 실패한다`() = runBlocking {
        // 준비
        var calls = 0
        val execution = execution(
            synth = SpeechExecutionPort {
                calls++
                SpeechSynthesisResult(byteArrayOf(1, 2, 3), "audio/wav", "synthetic", "fixture", 999.0)
            },
        )

        // 실행
        val failure = assertFailsWith<SpeakingStageFailure> {
            execution.synthesize("합성 발화", "fixture-invalid", "en", "marin", "NORMAL", policy)
        }

        // 검증
        assertEquals("TTS_FAILED", failure.code)
        assertFalse(failure.retryable)
        assertEquals(1, calls)
    }

    @Test
    fun `TTS 길이는 PCM에서 측정하고 캐시 성공은 공급자 사용량을 추가하지 않는다`() = runBlocking {
        // 준비
        var calls = 0
        var time = 0L
        val execution = execution(
            synth = SpeechExecutionPort {
                calls++
                SpeechSynthesisResult(wav(), "audio/wav", "synthetic", "fixture", 999.0)
            },
            time = { time },
        )

        // 실행
        val first = execution.synthesize("합성 발화", "fixture-cache", "en", "marin", "NORMAL", policy)
        val replay = execution.synthesize("합성 발화", "fixture-cache", "en", "marin", "NORMAL", policy)
        time = 3_600_000_000_001L
        execution.synthesize("합성 발화", "fixture-cache", "en", "marin", "NORMAL", policy)

        // 검증
        assertEquals(.01, first.durationSeconds)
        assertEquals(first.durationSeconds, replay.durationSeconds)
        assertEquals(2, calls)
        assertEquals(JsonNull, replay.usage.getValue("tts").jsonObject.getValue("provider"))
        assertEquals(0.0, replay.usage.getValue("tts").jsonObject.getValue("ttsAudioSeconds").jsonPrimitive.double)
    }

    @Test
    fun `원본 Provider 429만 기존 상한까지 재시도하고 새 프로토콜 오류는 그대로 전달한다`() = runBlocking {
        // 준비
        var retryCalls = 0
        val retry = execution(
            synth = SpeechExecutionPort {
                retryCalls++
                throw ModelExecutionFailure(
                    "PROVIDER_UNAVAILABLE", 503, true, failureKind = "RATE_LIMIT", providerStatus = 429,
                )
            },
        )
        var protocolCalls = 0
        val protocol = execution(
            synth = SpeechExecutionPort {
                protocolCalls++
                throw ModelExecutionFailure("SPEECH_EXECUTION_PROTOCOL", 502, false)
            },
        )

        // 실행
        val providerFailure = assertFailsWith<SpeakingStageFailure> {
            retry.synthesize("합성", "fixture-rate", "en", "marin", "NORMAL", policy)
        }
        val protocolFailure = assertFailsWith<ModelExecutionFailure> {
            protocol.synthesize("합성", "fixture-protocol", "en", "marin", "NORMAL", policy)
        }

        // 검증
        assertEquals("PROVIDER_RATE_LIMITED", providerFailure.code)
        assertEquals(3, retryCalls)
        assertEquals("SPEECH_EXECUTION_PROTOCOL", protocolFailure.code)
        assertEquals(1, protocolCalls)
    }

    @Test
    fun `음성 Provider ValueError는 원본 비재시도 실패다`() = runBlocking {
        // 준비
        var calls = 0
        val execution = execution(
            synth = SpeechExecutionPort {
                calls++
                throw ModelExecutionFailure("SPEECH_REQUEST_INVALID", 422, false, failureKind = "VALUE_ERROR")
            },
        )

        // 실행
        val failure = assertFailsWith<SpeakingStageFailure> {
            execution.synthesize("합성", "fixture-value", "en", "marin", "NORMAL", policy)
        }

        // 검증
        assertEquals("TTS_FAILED", failure.code)
        assertFalse(failure.retryable)
        assertEquals(1, calls)
    }

    private fun execution(
        source: SpeechTranscriptionPort = RecordingTranscription(listOf("합성"), false),
        synth: SpeechExecutionPort = SpeechExecutionPort { error("합성 호출 없음") },
        time: () -> Long = System::nanoTime,
    ) = SpeakingSpeechExecution(source, synth, "fixture", nanoTime = time)

    private class RecordingTranscription(private val responses: List<String>, private val evidence: Boolean) :
        SpeechTranscriptionPort {
        val calls = mutableListOf<SpeechTranscriptionCommand>()
        var evidenceCalls = 0
        override suspend fun normalize(command: AudioDecodeCommand) = audio
        override suspend fun transcribe(command: SpeechTranscriptionCommand): SpeechTranscriptionResult {
            val text = responses[calls.size]
            calls += command
            return SpeechTranscriptionResult(text, "en", .9, 3.0, emptyList(), "synthetic", "fixture", null)
        }

        override suspend fun hasSpeech(command: AudioDecodeCommand): Boolean {
            evidenceCalls++; return evidence
        }
    }

    companion object {
        private val audio = AudioDecodeResult(byteArrayOf(1), 3.0, "wav", .2, .8, .1, 16000, 1)
        private fun wav(): ByteArray = ByteBuffer.allocate(364).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(356); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(320); repeat(160) { putShort(1000) }
        }.array()
    }
}
