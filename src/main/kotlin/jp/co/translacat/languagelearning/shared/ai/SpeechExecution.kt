package jp.co.translacat.languagelearning.shared.ai

import java.time.Instant

internal data class SpeechSynthesisCommand(
    val text: String,
    val voice: String,
    val language: String,
    val speed: String,
    val deadlineUtc: Instant,
    val requestId: String,
) {
    init {
        require(text.isNotBlank() && text.length <= 4096)
        require(voice.isNotBlank() && language.length in 2..30)
        require(speed in setOf("NORMAL", "SLOW"))
        require(Regex("[A-Za-z0-9._:-]{1,100}").matches(requestId))
    }
}

internal data class SpeechSynthesisResult(
    val audioBytes: ByteArray,
    val contentType: String,
    val provider: String,
    val model: String,
    val durationSeconds: Double?,
)

internal fun interface SpeechExecutionPort {
    suspend fun synthesize(command: SpeechSynthesisCommand): SpeechSynthesisResult
}
