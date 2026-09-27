package jp.co.translacat.languagelearning.features.speaking.application

import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingFailure
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingSessionPolicySnapshot

import jp.co.translacat.languagelearning.shared.ai.AudioDecodeResult
import jp.co.translacat.languagelearning.shared.ai.SpeechTranscriptionResult
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.exp

internal object SpeakingTranscriptionPolicy {
    fun requireNormalized(audio: AudioDecodeResult, policy: SpeakingSessionPolicySnapshot) {
        if (audio.durationSeconds < policy.minValidAudioSeconds) throw SpeakingFailure("AUDIO_TOO_SHORT")
        if (audio.durationSeconds > policy.maxTurnAudioSeconds) throw SpeakingFailure("AUDIO_TOO_LONG")
        if (audio.rms < .003) throw SpeakingFailure("SILENCE_DETECTED")
    }

    fun normalizedValues(audio: AudioDecodeResult) = audio.copy(
        durationSeconds = rounded(audio.durationSeconds, 3),
        rms = rounded(audio.rms, 6), peak = rounded(audio.peak, 6), silenceRatio = rounded(audio.silenceRatio, 6),
    )

    fun transcript(
        result: SpeechTranscriptionResult, audio: AudioDecodeResult, requestedLanguage: String, threshold: Double = .55,
    ): JsonObject {
        // 텍스트와 segment를 원본처럼 정리하고 평균 segment 신뢰도를 우선한다.
        val text = result.text.trim()
        if (text.isEmpty()) throw SpeakingFailure("INVALID_AUDIO")
        val segments = result.segments.filter { it.text.isNotBlank() }.map { segment ->
            buildJsonObject {
                put("startMs", (segment.startSeconds * 1000).toInt().coerceAtLeast(0))
                put("endMs", (segment.endSeconds * 1000).toInt().coerceAtLeast(0))
                put("text", segment.text.trim())
                put("confidence", exp(segment.avgLogprob).coerceIn(0.0, 1.0))
            }
        }
        val confidence =
            (if (segments.isNotEmpty()) segments.map { it.getValue("confidence").jsonPrimitive.double }.average()
            else result.languageProbability ?: 0.0).coerceIn(0.0, 1.0)

        // low-confidence 판정은 반올림 전 값으로 수행하고 provenance·정규화 버전을 고정한다.
        return buildJsonObject {
            put("text", text)
            put("language", result.language?.takeIf(String::isNotEmpty) ?: requestedLanguage)
            put("confidence", rounded(confidence, 4))
            put("isLowConfidence", confidence < threshold)
            put("segments", JsonArray(segments))
            put(
                "metadata",
                buildJsonObject {
                    put("provider", result.provider)
                    put("model", result.model)
                    put("modelVersion", result.modelVersion?.let(::JsonPrimitive) ?: JsonNull)
                    put(
                        "detectedLanguage",
                        result.language?.takeIf(String::isNotEmpty)?.let(::JsonPrimitive) ?: JsonNull,
                    )
                    put("requestedLanguage", requestedLanguage)
                    put("lowConfidenceThreshold", threshold)
                    put("audioDuration", rounded(audio.durationSeconds, 3))
                    put(
                        "audioQualitySignals",
                        buildJsonObject {
                            put("rms", rounded(audio.rms, 6))
                            put("peak", rounded(audio.peak, 6))
                            put("silenceRatio", rounded(audio.silenceRatio, 6))
                            put("sampleRate", audio.sampleRate)
                            put("channels", audio.channels)
                        },
                    )
                    put("normalizationVersion", "speaking-audio-normalization")
                    put("sttHintVersion", "speaking-stt-hint")
                },
            )
        }
    }

    private fun rounded(value: Double, places: Int) =
        BigDecimal(value).setScale(places, RoundingMode.HALF_EVEN).toDouble()
}
