package jp.co.translacat.languagelearning.features.listening.application

import jp.co.translacat.languagelearning.features.listening.domain.model.*
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningAlignment
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningAlignmentEntry
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningScoring
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningText
import jp.co.translacat.languagelearning.shared.ai.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.*
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round

internal class ListeningRepeatExecution(
    private val speech: SpeechTranscriptionPort,
    private val timeoutSeconds: Long = 30,
    private val budget: ListeningExecutionBudget = ListeningExecutionBudget(),
) {
    suspend fun evaluate(
        audio: ByteArray, source: String, sourceSeconds: Double, language: String,
        phraseHints: List<String>, requestId: String, deadline: Instant,
        context: ListeningEvaluationContext, automaticRetryLimit: Int = 2,
    ): ListeningTaskResult {
        require(sourceSeconds > 0 && sourceSeconds <= 60 && automaticRetryLimit in 0..2)
        if (context.revealed) return ListeningScoring.revealed(ListeningTaskType.REPEAT_AFTER_AUDIO, context)
        if (audio.isEmpty()) return unavailable("AUDIO_DECODE_FAILED", context, message = "Audio가 비어 있습니다.")
        if (audio.size > 10 * 1024 * 1024) return unavailable(
            "AUDIO_TOO_LARGE", context, message = "Audio 파일은 10485760 bytes 이하여야 합니다.",
        )

        // 기술적 변환 결과를 받은 뒤 기존 음량·길이·음향 기준을 LL에서 판정한다.
        val decoded = try {
            speech.normalize(AudioDecodeCommand(audio, requestId, deadline))
        } catch (failure: ModelExecutionFailure) {
            if (failure.status == 422 && failure.code in setOf(
                    "AUDIO_BYTES_INVALID", "AUDIO_FORMAT_INVALID", "AUDIO_FRAMES_INVALID",
                    "AUDIO_DECODE_FAILED", "AUDIO_STREAM_MISSING", "AUDIO_FRAME_TYPE_INVALID",
                )
            ) return unavailable("AUDIO_DECODE_FAILED", context)
            throw failure
        }
        if (decoded.durationSeconds < .5) return unavailable("AUDIO_TOO_SHORT", context)
        if (decoded.durationSeconds > minOf(60.0, sourceSeconds + 15)) return unavailable(
            "AUDIO_TOO_LONG", context,
            message = "Turn Audio는 최대 ${String.format(Locale.ROOT, "%.1f", minOf(60.0, sourceSeconds + 15))}초까지 허용됩니다.",
        )
        if (decoded.rms < .003) return unavailable("SILENCE", context)
        // 기존 processor는 원시값으로 경계를 검사한 뒤 후속 평가에 반올림한 수치를 전달했다.
        val normalized = decoded.copy(
            durationSeconds = round(decoded.durationSeconds * 1000) / 1000,
            rms = round(decoded.rms * 1_000_000) / 1_000_000,
            peak = round(decoded.peak * 1_000_000) / 1_000_000,
            silenceRatio = round(decoded.silenceRatio * 1_000_000) / 1_000_000,
        )
        val acoustic = acoustic(normalized.audioBytes)
        val rmsScore = (normalized.rms / .02).coerceIn(0.0, 1.0)
        val peakScore = if (normalized.peak < .98) 1.0 else maxOf(.2, 1.0 - (normalized.peak - .98) * 40)
        val speechScore = ((1.0 - normalized.silenceRatio) / .80).coerceIn(0.0, 1.0)
        val base = (rmsScore * .40 + peakScore * .30 + speechScore * .30).coerceIn(0.0, 1.0)
        val quality =
            if (acoustic == null) base else (base * .45 + acoustic.clippingScore * .25 + acoustic.noiseScore * .30).coerceIn(
                0.0, 1.0,
            )
        if (quality < .25 || acoustic?.let { it.clippingRatio > .10 || it.noiseScore < .15 } == true) return unavailable(
            "LOW_AUDIO_QUALITY", context, round4(quality),
        )

        // 전사 한 번의 실패와 재시도를 구분하고 이미 소비한 deadline을 새로 만들지 않는다.
        var transcript: SpeechTranscriptionResult? = null
        for (attempt in 0..automaticRetryLimit) {
            try {
                transcript = budget.call(deadline, timeoutSeconds) { callDeadline ->
                    speech.transcribe(
                        SpeechTranscriptionCommand(
                            normalized.audioBytes, requestId, callDeadline,
                            "shared", null, (listOf(source) + phraseHints).take(20).joinToString(", "), 1, true, 500,
                            false,
                        ),
                    )
                }
                break
            } catch (failure: ModelExecutionFailure) {
                if (failure.code in setOf(
                        "SPEECH_EXECUTION_PROTOCOL", "MODEL_EXECUTION_PROTOCOL", "SPEECH_RESPONSE_TOO_LARGE",
                        "SPEECH_REQUEST_INVALID", "EXECUTION_REQUEST_INVALID",
                    )
                ) throw failure
                if (!failure.retryable || attempt == automaticRetryLimit || !Instant.now().isBefore(deadline))
                    return unavailable(
                        if (failure.code in setOf(
                                "PROVIDER_RATE_LIMITED", "PROVIDER_TIMEOUT", "PROVIDER_UNAVAILABLE", "UNSAFE_CONTENT",
                            )
                        ) failure.code else "STT_FAILED",
                        context,
                    )
            }
        }
        val result = checkNotNull(transcript)
        if (result.text.isBlank()) return unavailable("NON_SPEECH", context)
        val detected = result.language.orEmpty().lowercase().substringBefore('-')
        val probability = result.languageProbability ?: 0.0
        if (detected.isNotEmpty() && detected != language.lowercase().substringBefore('-') && probability >= .50)
            return unavailable("LANGUAGE_MISMATCH", context, round4(probability))

        // 기존 alignment·음향 수식만 사용한다. 음소 Provider 점수나 새 band는 만들지 않는다.
        val alignment = ListeningAlignment.align(
            ListeningText.normalize(source, language).tokens, ListeningText.normalize(result.text, language).tokens,
        )
        if (alignment.referenceCoverage < .30) return unavailable(
            "ALIGNMENT_INSUFFICIENT", context, round4(alignment.referenceCoverage),
        )
        val segmentConfidence = result.segments.map { exp(it.avgLogprob).coerceIn(0.0, 1.0) }
            .let { if (it.isEmpty()) probability else it.average() }
        val sttConfidence = (segmentConfidence * .70 + probability * .30).coerceIn(0.0, 1.0)
        val ratio = normalized.durationSeconds / sourceSeconds
        val tempo = if (ratio <= 0) 0.0 else maxOf(0.0, 1.0 - minOf(abs(ln(ratio) / ln(2.0)), 1.0))
        val speechRatio = 1 - normalized.silenceRatio
        val scores = linkedMapOf(
            "PRONUNCIATION" to ListeningScoring.roundScore(
                (alignment.recognitionRatio * .50 + sttConfidence * .25 + quality * .25) * 100,
            ),
            "PROSODY_RHYTHM" to ListeningScoring.roundScore((tempo * .60 + quality * .40) * 100),
            "FLUENCY" to ListeningScoring.roundScore((tempo * .45 + speechRatio * .35 + sttConfidence * .20) * 100),
            "COMPLETENESS" to ListeningScoring.roundScore(alignment.referenceCoverage * 100),
        )

        fun format(value: Double) = String.format(Locale.ROOT, "%.2f", value)
        val feedback = listOf(
            "Token alignment와 음향 품질(${format(quality)})을 함께 확인했습니다.",
            "Reference 대비 발화 길이 비율(${format(ratio)})과 음향 흐름을 확인했습니다.",
            "발화 구간 비율(${format(speechRatio)})과 속도 안정성을 확인했습니다.",
            "원문 Token Coverage(${format(alignment.referenceCoverage)})를 확인했습니다.",
        )
        val metrics = scores.entries.mapIndexed { index, (type, score) ->
            ListeningMetric(
                type, score.toDouble(),
                ListeningScoring.weights.getValue(ListeningTaskType.REPEAT_AFTER_AUDIO).getValue(type),
                round4(minOf(sttConfidence, maxOf(quality, .01))), listOf(ListeningEvidence(type, feedback[index])),
            )
        }
        val timestamped = timestamps(alignment.entries, result.segments)
        val evidence = timestamped.filter { it.status !in setOf("MATCH", "ACCEPTED_VARIANT") }.map {
            ListeningEvidence(
                "PRONUNCIATION", "원문과 다르게 정렬된 소리를 다시 따라 말해 보세요.",
                if (it.status in setOf("OMISSION", "SUBSTITUTION")) "HIGH" else "MEDIUM", it.startMs, it.endMs,
                it.source, it.answer,
            )
        } + metrics.flatMap { it.evidence }
        val score = checkNotNull(ListeningScoring.weighted(ListeningTaskType.REPEAT_AFTER_AUDIO, metrics))
        val labels = mapOf(
            "PRONUNCIATION" to "소리의 명료도", "PROSODY_RHYTHM" to "억양과 리듬", "FLUENCY" to "끊김 없는 발화",
            "COMPLETENESS" to "문장 완성도",
        )
        return ListeningScoring.finalize(
            ListeningTaskResult(
                ListeningTaskType.REPEAT_AFTER_AUDIO, "EVALUATED", true, score,
                round4((sttConfidence * .40 + alignment.referenceCoverage * .35 + quality * .25).coerceIn(0.0, 1.0)),
                metrics = metrics,
                alignment = timestamped, evidence = evidence,
                strengths = listOf(if (score >= 80) "원문의 핵심 Token과 흐름을 안정적으로 재현했습니다." else "인식된 구간을 끝까지 따라 말했습니다."),
                improvements = scores.entries.sortedBy { it.value }
                    .take(2)
                    .map { "다음 시도에서는 ${labels.getValue(it.key)}에 집중해 보세요." },
            ),
            context,
        )
    }

    private fun unavailable(
        code: String, context: ListeningEvaluationContext, confidence: Double? = null,
        message: String? = null,
    ) = ListeningTaskResult(
        ListeningTaskType.REPEAT_AFTER_AUDIO, "NOT_EVALUABLE", false, confidence = confidence, reasonCode = code,
        assistanceLevel = ListeningScoring.assistance(context), assistanceUsage = context.assistance,
        improvements = listOfNotNull(
            message ?: when (code) {
                "AUDIO_DECODE_FAILED" -> "Audio를 해석할 수 없습니다."
                "AUDIO_TOO_SHORT" -> "유효 Audio는 최소 0.5초 이상이어야 합니다."
                "SILENCE" -> "음성 신호를 확인할 수 없습니다."
                "LOW_AUDIO_QUALITY" -> "소음이 적고 음량이 안정적인 환경에서 다시 녹음해 주세요."
                "NON_SPEECH" -> "전사 가능한 발화가 확인되지 않았습니다."
                "LANGUAGE_MISMATCH" -> "학습 언어로 원문을 다시 따라 말해 주세요."
                "ALIGNMENT_INSUFFICIENT" -> "원문과 겹치는 발화가 부족합니다. 전체 문장을 다시 따라 말해 주세요."
                "PROVIDER_RATE_LIMITED" -> "AI Provider 요청 한도에 도달했습니다."
                "PROVIDER_TIMEOUT" -> "AI Provider 응답 시간이 초과되었습니다."
                "PROVIDER_UNAVAILABLE" -> "AI Provider를 일시적으로 사용할 수 없습니다."
                "UNSAFE_CONTENT" -> "안전 정책에 따라 요청을 처리할 수 없습니다."
                "STT_FAILED" -> "Repeat Audio 전사에 실패했습니다."
                else -> null
            },
        ),
    )

    private data class Acoustic(val clippingRatio: Double, val clippingScore: Double, val noiseScore: Double)

    private fun acoustic(bytes: ByteArray): Acoustic? {
        // Python 정규화는 mono PCM16 WAV를 반환한다. 해독 실패는 기존처럼 선택적 근거 없음으로 처리한다.
        return try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var offset = 12
            var samples: List<Float>? = null
            while (offset + 8 <= bytes.size) {
                val size = buffer.getInt(offset + 4)
                require(size >= 0 && offset.toLong() + 8 + size <= bytes.size)
                if (bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII) == "data")
                    samples = (offset + 8 until offset + 8 + size step 2).map { buffer.getShort(it) / 32768.0f }
                offset += 8 + size + size % 2
            }
            val values = checkNotNull(samples)
            if (values.size < 2) return null
            val clipped = values.count { abs(it) >= .98 }.toDouble() / values.size
            val crossing = values.zipWithNext().count { (a, b) -> a * b < 0 }.toDouble() / (values.size - 1)
            Acoustic(
                round(clipped * 1e6) / 1e6, round((1 - (clipped / .05).coerceIn(0.0, 1.0)) * 1e6) / 1e6,
                round((1 - ((crossing - .20) / .30).coerceIn(0.0, 1.0)) * 1e6) / 1e6,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun timestamps(
        entries: List<ListeningAlignmentEntry>, segments: List<SpeechTranscriptionSegment>,
    ): List<ListeningAlignmentEntry> {
        if (segments.isEmpty()) return entries
        val answers = entries.filter { it.answerIndex != null }
        if (answers.isEmpty()) return entries
        val start = segments.minOf { maxOf(0, (it.startSeconds * 1000).toInt()) }
        val end = segments.maxOf { maxOf(0, (it.endSeconds * 1000).toInt()) }
        val span = maxOf(end - start, 1)
        val times = answers.mapIndexed { index, entry ->
            entry.answerIndex to
                (start + span * index / answers.size to start + span * (index + 1) / answers.size)
        }.toMap()
        return entries.map { entry ->
            times[entry.answerIndex]?.let {
                entry.copy(
                    startMs = it.first, endMs = it.second,
                )
            } ?: entry
        }
    }

    private fun round4(value: Double) = round(value * 10000) / 10000
}
