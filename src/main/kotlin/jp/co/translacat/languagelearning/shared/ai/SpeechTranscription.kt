package jp.co.translacat.languagelearning.shared.ai

import java.time.Instant

internal data class AudioDecodeCommand(val audioBytes: ByteArray, val requestId: String, val deadlineUtc: Instant)
internal data class AudioDecodeResult(
    val audioBytes: ByteArray, val durationSeconds: Double, val sourceFormat: String,
    val rms: Double, val peak: Double, val silenceRatio: Double, val sampleRate: Int, val channels: Int,
)

internal data class SpeechTranscriptionCommand(
    val audioBytes: ByteArray,
    val requestId: String,
    val deadlineUtc: Instant,
    val runtime: String,
    val language: String?,
    val initialPrompt: String?,
    val beamSize: Int,
    val vadFilter: Boolean,
    val minSilenceDurationMs: Int,
    val conditionOnPreviousText: Boolean,
) {
    init {
        require(runtime in setOf("shared", "accurate") && beamSize in 1..5)
        require(minSilenceDurationMs in 0..10000)
    }
}

internal data class SpeechTranscriptionSegment(
    val startSeconds: Double, val endSeconds: Double, val text: String,
    val avgLogprob: Double, val noSpeechProbability: Double?,
)

internal data class SpeechTranscriptionResult(
    val text: String, val language: String?, val languageProbability: Double?, val durationSeconds: Double?,
    val segments: List<SpeechTranscriptionSegment>, val provider: String, val model: String, val modelVersion: String?,
)

internal interface SpeechTranscriptionPort {
    suspend fun normalize(command: AudioDecodeCommand): AudioDecodeResult
    suspend fun transcribe(command: SpeechTranscriptionCommand): SpeechTranscriptionResult
    suspend fun hasSpeech(command: AudioDecodeCommand): Boolean =
        throw ModelExecutionFailure("SPEECH_EVIDENCE_UNSUPPORTED", 503, false)
}
