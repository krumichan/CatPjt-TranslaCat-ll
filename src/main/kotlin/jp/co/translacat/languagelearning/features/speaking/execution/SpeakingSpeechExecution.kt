package jp.co.translacat.languagelearning.features.speaking.execution

import jp.co.translacat.languagelearning.features.speaking.application.SpeakingTranscriptionPolicy
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingFailure
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingSessionPolicySnapshot
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingWavDuration
import jp.co.translacat.languagelearning.shared.ai.*
import kotlinx.serialization.json.*
import java.time.Clock

internal data class SpeakingSynthesizedAudio(
    val bytes: ByteArray,
    val durationSeconds: Double,
    val cacheKey: String,
    val usage: JsonObject,
)

internal class SpeakingSpeechExecution(
    private val transcription: SpeechTranscriptionPort,
    private val synthesis: SpeechExecutionPort,
    private val ttsModel: String,
    private val clock: Clock = Clock.systemUTC(),
    private val nanoTime: () -> Long = System::nanoTime,
    private val beamSize: Int = 5,
) {
    private val sttCache = SpeakingExecutionCache(nanoTime)

    private data class CachedAudio(val created: Long, val bytes: ByteArray, val duration: Double)

    private val ttsCache = mutableMapOf<String, CachedAudio>()

    init {
        require(ttsModel.isNotBlank() && beamSize in 1..5)
    }

    suspend fun normalize(
        bytes: ByteArray, requestId: String, policy: SpeakingSessionPolicySnapshot,
    ): AudioDecodeResult {
        // 형식 변환은 Python의 기술 API가 맡고 업무상 최소 길이·무음 판정은 LL이 맡는다.
        if (bytes.isEmpty()) throw SpeakingStageFailure("INVALID_AUDIO", "AUDIO_VALIDATION", false)
        if (bytes.size > policy.maxAudioFileBytes) throw SpeakingStageFailure(
            "AUDIO_TOO_LARGE", "AUDIO_VALIDATION", false,
        )
        val normalized = try {
            transcription.normalize(
                AudioDecodeCommand(bytes, requestId, clock.instant().plusSeconds(policy.sttTimeoutSeconds.toLong())),
            )
        } catch (failure: ModelExecutionFailure) {
            val mapped = when (failure.code) {
                "AUDIO_FORMAT_INVALID" -> "UNSUPPORTED_AUDIO_FORMAT"
                "AUDIO_BYTES_INVALID", "AUDIO_FRAMES_INVALID" -> "INVALID_AUDIO"
                "AUDIO_DECODE_FAILED" -> if (bytes.size >= 12 && bytes.copyOfRange(0, 4)
                        .toString(Charsets.US_ASCII) == "RIFF" &&
                    bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE"
                ) "INVALID_AUDIO" else "UNSUPPORTED_AUDIO_FORMAT"

                "AUDIO_STREAM_MISSING", "AUDIO_FRAME_TYPE_INVALID" -> "UNSUPPORTED_AUDIO_FORMAT"
                else -> throw failure
            }
            throw SpeakingStageFailure(mapped, "AUDIO_VALIDATION", false)
        }
        try {
            SpeakingTranscriptionPolicy.requireNormalized(normalized, policy)
        } catch (failure: SpeakingFailure) {
            throw SpeakingStageFailure(failure.code, "AUDIO_VALIDATION", false)
        }
        // 원본은 통과 여부를 원래 값으로 판단한 뒤 사용량·후속 단계에 반올림된 정규화 값을 전달한다.
        return SpeakingTranscriptionPolicy.normalizedValues(normalized)
    }

    suspend fun transcribe(
        audio: AudioDecodeResult,
        requestId: String,
        idempotencyKey: String,
        learningLanguage: String,
        phraseHints: List<String>,
        policy: SpeakingSessionPolicySnapshot,
        manualRetryAttempt: Int = 0,
    ): JsonObject {
        if (manualRetryAttempt > 1) throw SpeakingStageFailure("MANUAL_RETRY_LIMIT_EXCEEDED", "STT", false)
        val retries = minOf(2, policy.automaticRetryLimitPerStage)
        val deadline = clock.instant().plusSeconds((retries + 1) * policy.sttTimeoutSeconds.toLong())
        return sttCache.execute(idempotencyKey) {
            val started = nanoTime()
            val result = speakingStageRetry(retries, "STT", deadline, clock) {
                // 한 시도의 30초 안에 VAD 추론·독립 발화 검사·최대 한 번의 no-VAD 보정을 묶는다.
                val attemptDeadline = minOf(deadline, clock.instant().plusSeconds(policy.sttTimeoutSeconds.toLong()))
                val command = SpeechTranscriptionCommand(
                    audio.audioBytes, requestId, attemptDeadline, "accurate",
                    learningLanguage, phraseHints.take(20).takeIf { it.isNotEmpty() }?.joinToString(", "),
                    beamSize, true, 500, false,
                )
                try {
                    val first = transcription.transcribe(command)
                    if (first.text.isBlank() && transcription.hasSpeech(
                            AudioDecodeCommand(audio.audioBytes, requestId, attemptDeadline),
                        )
                    ) {
                        transcription.transcribe(command.copy(vadFilter = false))
                    } else first
                } catch (failure: ModelExecutionFailure) {
                    throw speechFailure(failure, "STT", "STT_FAILED")
                }
            }

            // 빈 전사와 음향 후처리 오류는 재시도 루프 밖에서 기존 정책대로 실패시킨다.
            val transcript = try {
                SpeakingTranscriptionPolicy.transcript(result, audio, learningLanguage)
            } catch (failure: SpeakingFailure) {
                throw SpeakingStageFailure(failure.code, "STT", false)
            }
            buildJsonObject {
                put("transcript", transcript)
                put(
                    "usage",
                    speakingStageUsage(
                        "stt", elapsed(started), result.provider, result.model,
                        audioSeconds = audio.durationSeconds,
                    ),
                )
            }
        }.first
    }

    suspend fun synthesize(
        text: String,
        requestId: String,
        learningLanguage: String,
        voice: String,
        playbackSpeed: String,
        policy: SpeakingSessionPolicySnapshot,
        manualRetryAttempt: Int = 0,
    ): SpeakingSynthesizedAudio {
        if (manualRetryAttempt > 1) throw SpeakingStageFailure("MANUAL_RETRY_LIMIT_EXCEEDED", "TTS", false)
        val key = listOf(
            learningLanguage, voice, playbackSpeed, text, "openai", ttsModel, "openai-speech-v1", "speaking-tts",
        ).joinToString("|")
        val cached = synchronized(ttsCache) {
            purgeAudio()
            ttsCache[key]
        }
        if (cached != null) return SpeakingSynthesizedAudio(
            cached.bytes.copyOf(), cached.duration, key,
            speakingStageUsage(
                "tts", 0, characters = text.codePointCount(0, text.length), promptVersion = "speaking-tts",
            ),
        )

        // 원본과 같이 동시 첫 생성은 허용하며 완료된 첫 음성과 그 길이를 함께 캐시한다.
        val started = nanoTime()
        val retries = minOf(2, policy.automaticRetryLimitPerStage)
        val deadline = clock.instant().plusSeconds((retries + 1) * policy.ttsTimeoutSeconds.toLong())
        val result = speakingStageRetry(retries, "TTS", deadline, clock) {
            try {
                synthesis.synthesize(
                    SpeechSynthesisCommand(
                        text, voice, learningLanguage, playbackSpeed,
                        minOf(deadline, clock.instant().plusSeconds(policy.ttsTimeoutSeconds.toLong())), requestId,
                    ),
                )
            } catch (failure: ModelExecutionFailure) {
                throw speechFailure(failure, "TTS", "TTS_FAILED")
            }
        }

        // 완료 WAV 검사는 재시도 후 수행한다. 손상된 음성을 추가 생성으로 감추지 않는다.
        val duration = try {
            SpeakingWavDuration.seconds(result.audioBytes)
        } catch (_: IllegalArgumentException) {
            throw SpeakingStageFailure("TTS_FAILED", "TTS", false)
        }
        val stored = synchronized(ttsCache) {
            purgeAudio()
            ttsCache.getOrPut(key) { CachedAudio(nanoTime(), result.audioBytes.copyOf(), duration) }
        }
        return SpeakingSynthesizedAudio(
            stored.bytes.copyOf(), stored.duration, key,
            speakingStageUsage(
                "tts", elapsed(started), result.provider, result.model,
                characters = text.codePointCount(0, text.length), ttsSeconds = duration, promptVersion = "speaking-tts",
            ),
        )
    }

    private fun purgeAudio() {
        ttsCache.entries.removeIf { nanoTime() - it.value.created > 3_600_000_000_000L }
    }

    private fun elapsed(started: Long) = ((nanoTime() - started) / 1_000_000).coerceAtLeast(0)

    private fun speechFailure(failure: ModelExecutionFailure, stage: String, fallback: String): SpeakingStageFailure {
        // 업무 이행 중 새로 생긴 HTTP·DTO 오류는 기존 Provider 실패로 둔갑시키지 않는다.
        if (failure.code in setOf("SPEECH_DEADLINE_EXCEEDED", "SPEECH_EVIDENCE_TIMEOUT")) {
            return SpeakingStageFailure("PROVIDER_TIMEOUT", stage, true)
        }
        if (failure.code == "PROVIDER_RATE_LIMITED") return SpeakingStageFailure("PROVIDER_RATE_LIMITED", stage, true)
        if (stage == "STT" && failure.code == "PROVIDER_REFUSAL") return SpeakingStageFailure(
            "UNSAFE_TOPIC", stage, false,
        )
        if (failure.code == "SPEECH_EVIDENCE_UNAVAILABLE") return SpeakingStageFailure(fallback, stage, true)
        if (failure.code.startsWith("SPEECH_") && failure.failureKind == null) throw failure
        return speakingProviderFailure(failure, stage, fallback)
    }
}

internal fun speakingStageUsage(
    stage: String, latency: Long, provider: String? = null, model: String? = null,
    audioSeconds: Double = 0.0, characters: Int = 0, ttsSeconds: Double = 0.0,
    promptVersion: String? = null,
): JsonObject = buildJsonObject {
    listOf("stt", "conversation", "tts", "evaluation").forEach { put(it, JsonNull) }
    put(
        stage,
        buildJsonObject {
            put("latencyMs", latency); put("inputTokens", 0); put("outputTokens", 0)
            put("audioSeconds", audioSeconds); put("ttsCharacters", characters); put("ttsAudioSeconds", ttsSeconds)
            put("provider", provider?.let(::JsonPrimitive) ?: JsonNull); put(
            "model", model?.let(::JsonPrimitive) ?: JsonNull,
        )
            put("promptVersion", promptVersion?.let(::JsonPrimitive) ?: JsonNull); put("evaluationVersion", JsonNull)
        },
    )
}
