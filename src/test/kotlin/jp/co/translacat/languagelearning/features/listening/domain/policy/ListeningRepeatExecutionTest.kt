package jp.co.translacat.languagelearning.features.listening.domain.policy

import jp.co.translacat.languagelearning.features.listening.application.ListeningRepeatExecution
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningEvaluationContext
import jp.co.translacat.languagelearning.shared.ai.*
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ListeningRepeatExecutionTest {
    @Test
    fun `원시 무음 경계를 반올림하기 전에 검사한다`() = runTest {
        // 준비
        var transcribed = false
        val speech = object : SpeechTranscriptionPort {
            override suspend fun normalize(command: AudioDecodeCommand) =
                AudioDecodeResult(byteArrayOf(1), 1.0, "wav", .0029996, .01, 0.0, 16000, 1)

            override suspend fun transcribe(command: SpeechTranscriptionCommand): SpeechTranscriptionResult {
                transcribed = true
                error("무음 판정 후 전사가 실행되면 안 됩니다.")
            }
        }

        // 실행
        val result = ListeningRepeatExecution(speech).evaluate(
            byteArrayOf(1), "Synthetic sentence", 2.0, "en", emptyList(),
            "synthetic-repeat", Instant.now().plusSeconds(30), ListeningEvaluationContext(),
        )

        // 검증
        assertEquals("SILENCE", result.reasonCode)
        assertFalse(transcribed)
    }

    @Test
    fun `STT 프로토콜 오류는 학습 평가 불가로 바꾸지 않는다`() = runTest {
        // 준비
        val speech = object : SpeechTranscriptionPort {
            override suspend fun normalize(command: AudioDecodeCommand) =
                AudioDecodeResult(byteArrayOf(1), 2.0, "wav", .1, .2, .1, 16000, 1)

            override suspend fun transcribe(command: SpeechTranscriptionCommand): SpeechTranscriptionResult =
                throw ModelExecutionFailure("SPEECH_EXECUTION_PROTOCOL", 502, false)
        }

        // 실행
        val failure = assertFailsWith<ModelExecutionFailure> {
            ListeningRepeatExecution(speech).evaluate(
                byteArrayOf(1), "Synthetic sentence", 2.0, "en", emptyList(),
                "synthetic-repeat", Instant.now().plusSeconds(30), ListeningEvaluationContext(),
            )
        }

        // 검증
        assertEquals("SPEECH_EXECUTION_PROTOCOL", failure.code)
        assertFalse(failure.retryable)
    }
}
