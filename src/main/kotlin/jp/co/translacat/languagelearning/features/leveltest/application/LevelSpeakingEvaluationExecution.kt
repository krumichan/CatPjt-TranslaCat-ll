package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import jp.co.translacat.languagelearning.shared.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import kotlin.math.exp

/** 음성 디코딩·전사는 기술 포트에 맡기고 기존 Level Test의 평가·실패 판정은 LL에서 수행한다. */
internal class LevelSpeakingEvaluationExecution(
    private val speech: SpeechTranscriptionPort,
    private val model: ModelExecutionPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun evaluate(
        session: LevelSession, item: LevelItem, submission: LevelSubmission,
        bytes: ByteArray, deadline: Instant,
    ): LevelEvaluationData {
        val request = request(session, item, submission)
        val key = request.getValue("requestId").jsonPrimitive.content
        val sttStarted = clock.millis()
        val decoded: AudioDecodeResult
        val transcript: JsonObject

        // 업로드 크기·길이·무음 검사와 전사 실패는 기존처럼 평가 불가 결과로 기록한다.
        try {
            if (bytes.isEmpty()) throw ModelExecutionFailure("INVALID_AUDIO", 400, false)
            if (bytes.size > 10 * 1024 * 1024) throw ModelExecutionFailure("AUDIO_TOO_LARGE", 400, false)
            decoded = speech.normalize(AudioDecodeCommand(bytes, key, deadline))
            if (decoded.durationSeconds < 1.0) throw ModelExecutionFailure("AUDIO_TOO_SHORT", 400, false)
            if (decoded.durationSeconds > request.getValue("maxDurationSeconds").jsonPrimitive.int)
                throw ModelExecutionFailure("AUDIO_TOO_LONG", 400, false)
            if (decoded.rms < 0.003) throw ModelExecutionFailure("SILENCE_DETECTED", 400, false)
            if (submission.manualRetryCount > 1) throw ModelExecutionFailure("MANUAL_RETRY_LIMIT_EXCEEDED", 400, false)
            transcript = transcribe(request, decoded, deadline)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ModelExecutionFailure) {
            // 새 HTTP/DTO/protocol 오류는 기존 음성 평가 불가로 덮지 않는다.
            val reason = audioFailureReason(failure.code) ?: throw failure
            return failed(key, session, item, reason)
        }
        val sttLatency = (clock.millis() - sttStarted).coerceAtLeast(0)
        val confidence = transcript.getValue("confidence").jsonPrimitive.double
        if (confidence < 0.30) return failed(
            key, session, item, "LOW_STT_CONFIDENCE", transcript,
            usage(
                sttLatency, provider = transcript.getValue("metadata").jsonObject["provider"],
                model = transcript.getValue("metadata").jsonObject["model"],
            ),
        )

        // 기존 3회·회당 60초 평가 한도를 전체 작업 deadline 안에서 유지한다.
        val prompt =
            "Evaluate this single Level Test speaking response. Do not calculate a cross-item/domain score.\n\n" +
                JsonObject(request + mapOf("transcript" to transcript, "acousticQuality" to quality(decoded)))
        var inputTokens = 0
        var outputTokens = 0
        val evaluationStarted = clock.millis()
        for (attempt in 1..3) {
            try {
                val result = model.execute(
                    ModelExecutionCommand(
                        key, LevelSpeakingEvaluationPolicy.instructions,
                        listOf(ModelMessage("user", prompt)), ModelTier.MINI, 8192,
                        minOf(deadline, clock.instant().plusSeconds(60)), LevelSpeakingEvaluationPolicy.schema,
                        "LevelTestSpeakingEvaluationPayload",
                        taskName = "LANGUAGE_LEARNING_LEVEL_TEST_SPEAKING_EVALUATION",
                    ),
                )
                inputTokens += result.inputTokens
                outputTokens += result.outputTokens
                val parsed =
                    LevelSpeakingEvaluationPolicy.parse(result.output, item.data.itemType, session.originLanguage)
                return response(
                    key, session, item, transcript, parsed,
                    usage(
                        sttLatency + (clock.millis() - evaluationStarted).coerceAtLeast(0), inputTokens,
                        outputTokens, JsonPrimitive(result.provider), JsonPrimitive(result.model), true,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: LevelSpeakingProtocolFailure) {
                if (attempt == 3 || clock.instant() >= deadline)
                    throw LevelTestException(
                        "LEVEL_TEST_SPEAKING_SCHEMA_INVALID", 502, "Speaking 평가 응답 Schema가 유효하지 않습니다.",
                    )
            } catch (failure: ModelExecutionFailure) {
                if (!LevelModelFailurePolicy.retryGeneration(failure) || attempt == 3 || clock.instant() >= deadline)
                    throw LevelTestException(
                        failure.code, LevelModelFailurePolicy.status(failure),
                        "Level Test Speaking 평가 Provider 호출에 실패했습니다.",
                    )
            }
        }
        error("도달할 수 없는 Speaking 평가 상태입니다.")
    }

    private suspend fun transcribe(request: JsonObject, decoded: AudioDecodeResult, deadline: Instant): JsonObject {
        val key = request.getValue("requestId").jsonPrimitive.content
        val language = request.getValue("learningLanguage").jsonPrimitive.content
        val hints = request.getValue("phraseHints").jsonArray.map { it.jsonPrimitive.content }
        for (attempt in 0..2) {
            try {
                // 에너지 크기만으로 VAD를 해제하지 않고 독립적인 발화 증거가 있는 경우만 한 번 재전사한다.
                val command = SpeechTranscriptionCommand(
                    decoded.audioBytes, key, minOf(deadline, clock.instant().plusSeconds(30)),
                    "accurate", language, hints.takeIf { it.isNotEmpty() }?.take(20)?.joinToString(", "),
                    1, true, 500, false,
                )
                var result = speech.transcribe(command)
                if (result.text.isBlank() && speech.hasSpeech(
                        AudioDecodeCommand(decoded.audioBytes, key, command.deadlineUtc),
                    )
                )
                    result = speech.transcribe(command.copy(vadFilter = false))
                if (result.text.isBlank()) throw ModelExecutionFailure("INVALID_AUDIO", 400, false)
                return transcript(result, decoded, language)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ModelExecutionFailure) {
                val actual = if (failure.code == "SPEECH_DEADLINE_EXCEEDED" && clock.instant() < deadline)
                    ModelExecutionFailure("PROVIDER_TIMEOUT", 504, true) else failure
                if (!actual.retryable || attempt == 2 || clock.instant() >= deadline) throw actual
            }
        }
        error("도달할 수 없는 전사 상태입니다.")
    }

    internal fun request(session: LevelSession, item: LevelItem, submission: LevelSubmission): JsonObject {
        val key = "lt:${session.uid}:eval:${item.id}:v${submission.revision}:r${submission.manualRetryCount}"
        val ref = item.data.referencePayload
        return buildJsonObject {
            put("requestId", key); put("idempotencyKey", key); put("sessionId", session.id); put("itemId", item.id)
            put("itemType", item.data.itemType.name); put("promptText", item.data.promptText)
            put("referenceText", ref["referenceText"] ?: JsonNull)
            put("originLanguage", session.originLanguage); put("learningLanguage", session.learningLanguage)
            put("complexityBand", item.data.complexityBand); put(
            "maxDurationSeconds", (item.data.maxAudioSeconds ?: 30).coerceAtLeast(3),
        )
            listOf("phraseHints", "providedFacts", "requiredIntents", "responseConstraints").forEach {
                put(it, ref[it] as? JsonArray ?: JsonArray(emptyList()))
            }
            put("manualRetryAttempt", submission.manualRetryCount)
        }
    }

    internal fun transcript(
        result: SpeechTranscriptionResult, decoded: AudioDecodeResult, language: String,
    ): JsonObject {
        val segments = result.segments.filter { it.text.isNotBlank() }.map { segment ->
            buildJsonObject {
                put("startMs", (segment.startSeconds * 1000).toInt().coerceAtLeast(0))
                put("endMs", (segment.endSeconds * 1000).toInt().coerceAtLeast(0))
                put("text", segment.text.trim()); put("confidence", exp(segment.avgLogprob).coerceIn(0.0, 1.0))
            }
        }
        val confidence =
            (segments.takeIf { it.isNotEmpty() }?.map { it.getValue("confidence").jsonPrimitive.double }?.average()
                ?: result.languageProbability ?: 0.0).coerceIn(0.0, 1.0)
        return buildJsonObject {
            put("text", result.text.trim()); put("language", result.language?.takeIf { it.isNotEmpty() } ?: language)
            put("confidence", rounded(confidence, 4)); put("isLowConfidence", confidence < 0.55)
            put("segments", JsonArray(segments))
            put(
                "metadata",
                buildJsonObject {
                    put("provider", result.provider); put("model", result.model)
                    put("modelVersion", result.modelVersion?.let(::JsonPrimitive) ?: JsonNull)
                    put("detectedLanguage", result.language?.takeIf { it.isNotEmpty() } ?: language)
                    put("requestedLanguage", language); put("lowConfidenceThreshold", 0.55)
                    put("audioDuration", rounded(decoded.durationSeconds, 3)); put(
                    "audioQualitySignals", quality(decoded),
                )
                    put("normalizationVersion", "speaking-audio-normalization"); put(
                    "sttHintVersion", "speaking-stt-hint",
                )
                },
            )
        }
    }

    internal fun response(
        key: String, session: LevelSession, item: LevelItem, transcript: JsonObject,
        evaluated: LevelSpeakingEvaluation, usage: JsonObject,
    ): LevelEvaluationData {
        val payload = evaluated.payload
        val metrics = payload.getValue("metrics").jsonArray.map { element ->
            val metric = element.jsonObject
            JsonObject(
                metric + ("evidence" to JsonArray(
                    metric.getValue("evidence").jsonArray.map {
                        buildJsonObject { put("message", it) }
                    },
                )),
            )
        }
        val details = mutableListOf<JsonObject>()
        metrics.forEach { metric ->
            val category = metric.getValue("type").jsonPrimitive.content
            val score = metric.getValue("score").jsonPrimitive.doubleOrNull
            details += detail(
                category, if (score != null && score < 90) "IMPROVEMENT" else "INFO",
                metric.getValue("summary").jsonPrimitive.content,
            )
            metric.getValue("evidence").jsonArray.forEach { evidence ->
                val text = evidence.jsonObject.getValue("message").jsonPrimitive.content.trim()
                if (text.isNotEmpty()) details += detail(category, "IMPROVEMENT", text)
            }
        }
        val improvements = payload.getValue("improvements").jsonArray.map { it.jsonPrimitive.content }
        improvements.filter { it.isNotEmpty() && details.none { detail -> detail["explanation"] == JsonPrimitive(it) } }
            .forEach { details += detail("TASK", "IMPROVEMENT", it) }
        val signals = if (evaluated.score == null) emptyList() else metrics.filter {
            it["state"] == JsonPrimitive("EVALUATED") && it["score"] != JsonNull
        }.map { metric ->
            buildJsonObject {
                put("domain", "SPEAKING"); put("metric", metric.getValue("type"))
                put("score", metric.getValue("score")); put("confidence", metric.getValue("confidence"))
            }
        }

        // 반복 과제는 원문을 권장 답변으로 유지하고 공식 성장 반영은 기존 세션 완료 경계에 남긴다.
        val reference = item.data.referencePayload["referenceText"]?.jsonPrimitive?.contentOrNull
        return LevelEvaluationData(
            key, session.id, item.id, LevelTestDomain.SPEAKING, item.data.itemType,
            evaluated.score != null, evaluated.score, payload.getValue("evaluationConfidence").jsonPrimitive.double,
            transcript.getValue("text").jsonPrimitive.content, metrics,
            payload.getValue("strengths").jsonArray.map { it.jsonPrimitive.content }, improvements,
            if (item.data.itemType == LevelTestItemType.SPEAKING_REPEAT && !reference.isNullOrEmpty()) listOf(reference)
            else payload.getValue("recommendedAnswers").jsonArray.map { it.jsonPrimitive.content },
            details.take(50), signals, if (evaluated.score == null) "INSUFFICIENT_EVIDENCE" else null,
            "level-test-speaking-evaluation", "level-test-speaking-evaluation-prompt", usage,
        )
    }

    private fun failed(
        key: String, session: LevelSession, item: LevelItem, reason: String,
        transcript: JsonObject? = null, usage: JsonObject = usage(0),
    ): LevelEvaluationData =
        LevelEvaluationData(
            key, session.id, item.id, LevelTestDomain.SPEAKING, item.data.itemType, false,
            confidence = transcript?.getValue("confidence")?.jsonPrimitive?.double,
            transcript = transcript?.getValue("text")?.jsonPrimitive?.content, reasonCode = reason,
            evaluationVersion = "level-test-speaking-evaluation",
            promptVersion = "level-test-speaking-evaluation-prompt", usage = usage,
        )

    private fun quality(decoded: AudioDecodeResult) = buildJsonObject {
        put("rms", rounded(decoded.rms, 6)); put("peak", rounded(decoded.peak, 6)); put(
        "silenceRatio", rounded(decoded.silenceRatio, 6),
    )
        put("sampleRate", decoded.sampleRate); put("channels", decoded.channels)
    }

    private fun rounded(value: Double, places: Int) =
        BigDecimal(value).setScale(places, RoundingMode.HALF_EVEN).toDouble()

    private fun audioFailureReason(code: String): String? = when (code) {
        "INVALID_AUDIO", "AUDIO_TOO_LARGE", "AUDIO_TOO_SHORT", "AUDIO_TOO_LONG", "SILENCE_DETECTED",
        "MANUAL_RETRY_LIMIT_EXCEEDED", "PROVIDER_RATE_LIMITED", "PROVIDER_TIMEOUT",
            -> code

        "AUDIO_BYTES_INVALID", "AUDIO_FRAMES_INVALID", "AUDIO_FORMAT_INVALID", "AUDIO_DECODE_FAILED" -> "INVALID_AUDIO"
        "AUDIO_DECODE_LIMIT_EXCEEDED" -> "AUDIO_TOO_LONG"
        "STT_EXECUTION_FAILED", "SPEECH_EVIDENCE_UNAVAILABLE" -> "STT_FAILED"
        "SPEECH_EVIDENCE_TIMEOUT" -> "PROVIDER_TIMEOUT"
        "PROVIDER_REFUSAL" -> "UNSAFE_TOPIC"
        else -> null
    }

    private fun detail(category: String, severity: String, explanation: String) = buildJsonObject {
        put("category", category); put("severity", severity); put("original", JsonNull); put(
        "corrected", JsonNull,
    ); put("explanation", explanation)
    }

    internal fun usage(
        latencyMs: Long, input: Int = 0, output: Int = 0, provider: JsonElement? = null,
        model: JsonElement? = null, evaluated: Boolean = false,
    ) = buildJsonObject {
        put("latencyMs", latencyMs); put("inputTokens", input); put("outputTokens", output)
        put("provider", provider ?: JsonNull); put("model", model ?: JsonNull)
        put("promptVersion", if (evaluated) JsonPrimitive("level-test-speaking-evaluation-prompt") else JsonNull)
        put("evaluationVersion", if (evaluated) JsonPrimitive("level-test-speaking-evaluation") else JsonNull)
    }
}
