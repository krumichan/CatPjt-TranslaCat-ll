package jp.co.translacat.languagelearning.features.speaking.domain

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

@Serializable
internal enum class SpeakingPracticeMode { READ_ALOUD, GUIDED, FREE }
@Serializable
internal enum class ConversationStartMode { AI_FIRST, USER_FIRST, TOPIC_RECOMMENDED }
@Serializable
internal enum class CorrectionMode { CONVERSATION, COACHING }
@Serializable
internal enum class SpeakingAssistanceType { REPLAY, SLOW_PLAYBACK, SHOW_QUESTION, HINT, TRANSLATION, SAMPLE_ANSWER }
@Serializable
internal enum class SpeakingAssistanceLevel { NONE, ASSISTED, GUIDED }
@Serializable
internal enum class SpeakingMetricType { GRAMMAR, VOCABULARY, NATURALNESS, MEANING, EXPRESSIVENESS, FLUENCY, PRONUNCIATION, INTERACTION }
@Serializable
internal enum class SpeakingMetricState { EVALUATED, NOT_EVALUABLE }

@Serializable
internal data class SpeakingEvidenceTurn(
    val turnId: String,
    val transcript: String,
    val sttConfidence: Double,
    val durationSeconds: Double,
    val excludedFromEvaluation: Boolean = false,
    val segmentConfidences: List<Double> = emptyList(),
)

@Serializable
internal data class SpeakingEligibility(
    val validUserTurns: Int,
    val validUserSpeechSeconds: Double,
    val validSttTurnRatio: Double,
    val requiredUserTurns: Int,
    val requiredSpeechSeconds: Double,
    val requiredSttTurnRatio: Double,
    val eligibleBeforeAi: Boolean,
    val missingRequirements: List<String>,
    val requiredEvaluationConfidence: Double = .7,
)

@Serializable
internal data class SpeakingMetricValue(
    val type: SpeakingMetricType,
    val state: SpeakingMetricState,
    val score: Double?,
)

internal data class SpeakingCompletionTurn(
    val transcript: String?,
    val durationSeconds: Double,
    val excludedFromEvaluation: Boolean,
    val awaitingUpload: Boolean,
)

internal object SpeakingPolicy {
    const val SCORING_VERSION = "speaking-scoring-policy-v2"
    const val EVALUATION_VERSION = "speaking-evaluation-v2"
    const val EVALUATION_PROMPT_VERSION = "speaking-evaluation-prompt-v3"
    const val CONVERSATION_PROMPT_VERSION = "speaking-conversation-v2"
    const val EVIDENCE_VERSION = "speaking-transcript-evidence-v2"
    const val EVIDENCE_SOURCE = "TRANSCRIPT_OBSERVATION"

    val weights = linkedMapOf(
        SpeakingMetricType.GRAMMAR to .10,
        SpeakingMetricType.VOCABULARY to .10,
        SpeakingMetricType.NATURALNESS to .10,
        SpeakingMetricType.MEANING to .10,
        SpeakingMetricType.EXPRESSIVENESS to .10,
        SpeakingMetricType.FLUENCY to .20,
        SpeakingMetricType.PRONUNCIATION to .15,
        SpeakingMetricType.INTERACTION to .15,
    )

    fun assistanceLevel(types: Iterable<SpeakingAssistanceType>): SpeakingAssistanceLevel {
        val used = types.toSet()
        return when {
            SpeakingAssistanceType.SAMPLE_ANSWER in used -> SpeakingAssistanceLevel.GUIDED
            SpeakingAssistanceType.HINT in used || SpeakingAssistanceType.TRANSLATION in used -> SpeakingAssistanceLevel.ASSISTED
            else -> SpeakingAssistanceLevel.NONE
        }
    }

    fun eligibility(
        turns: List<SpeakingEvidenceTurn>,
        minSttConfidence: Double = .55,
        requiredUserTurns: Int = 5,
        requiredSpeechSeconds: Double = 60.0,
        requiredSttTurnRatio: Double = .80,
    ): SpeakingEligibility {
        // 원본 사전검사는 제외되지 않은 발화 전체를 비율 분모와 시간 집계에 쓴다.
        val included = turns.filterNot { it.excludedFromEvaluation }
        val count = included.count { it.transcript.isNotBlank() }
        val seconds = included.filter { it.durationSeconds > 0 }.sumOf { it.durationSeconds }
        val reliable = included.count { it.transcript.isNotBlank() && it.sttConfidence >= minSttConfidence }
        val ratio = if (included.isEmpty()) 0.0 else reliable.toDouble() / included.size

        // 판정은 반올림 전 수치로 수행하며 표시 수치만 Python의 HALF_EVEN에 맞춘다.
        val missing = buildList {
            if (count < requiredUserTurns) add("VALID_USER_TURNS")
            if (seconds < requiredSpeechSeconds) add("VALID_SPEECH_SECONDS")
            if (ratio < requiredSttTurnRatio) add("VALID_STT_TURN_RATIO")
        }
        return SpeakingEligibility(
            count, rounded(seconds, 3), rounded(ratio, 4), requiredUserTurns,
            requiredSpeechSeconds, requiredSttTurnRatio, missing.isEmpty(), missing,
        )
    }

    fun transcriptUsable(turn: SpeakingEvidenceTurn, threshold: Double = .55): Boolean =
        !turn.excludedFromEvaluation && turn.transcript.isNotBlank() && turn.sttConfidence >= threshold &&
            turn.segmentConfidences.all { it >= threshold }

    fun completionEligibility(mode: SpeakingPracticeMode, turns: List<SpeakingCompletionTurn>): SpeakingEligibility {
        // Core 화면·완료 조건은 업로드 대기를 제외하고 transcript 존재만 집계했다.
        // 모델 호출 전 STT 신뢰도 검사는 eligibility()에서 원래대로 별도 수행한다.
        val included = turns.filterNot { it.excludedFromEvaluation || it.awaitingUpload }
        val valid = included.filter { !it.transcript.isNullOrBlank() }
        val seconds = valid.sumOf { it.durationSeconds }
        val ratio = if (included.isEmpty()) 0.0 else valid.size.toDouble() / included.size
        val requiredTurns = if (mode == SpeakingPracticeMode.READ_ALOUD) 10 else 5
        val requiredSeconds = if (mode == SpeakingPracticeMode.READ_ALOUD) 0.0 else 60.0

        // Core 고유 오류 코드와 소수 둘째 자리 표시 규칙을 보존한다.
        val missing = buildList {
            if (valid.size < requiredTurns) add("VALID_USER_TURNS")
            if (seconds < requiredSeconds) add("VALID_USER_SPEECH_SECONDS")
            if (ratio < .80) add("VALID_STT_TURN_RATIO")
        }
        return SpeakingEligibility(
            valid.size, Math.round(seconds * 100) / 100.0,
            Math.round(ratio * 100) / 100.0, requiredTurns, requiredSeconds, .80, missing.isEmpty(), missing,
        )
    }

    fun unsupportedMetrics(mode: SpeakingPracticeMode): Map<SpeakingMetricType, String> {
        // STT 메타데이터나 오디오 참조만으로 텍스트 평가기에 음향 평가 능력을 부여하지 않는다.
        val unsupported = linkedMapOf(
            SpeakingMetricType.PRONUNCIATION to "TEXT_EVALUATOR_HAS_NO_ACOUSTIC_EVIDENCE",
            SpeakingMetricType.FLUENCY to "NO_VERIFIED_TEMPORAL_FLUENCY_MEASUREMENTS",
        )
        if (mode == SpeakingPracticeMode.READ_ALOUD) {
            SpeakingMetricType.entries.filter { it != SpeakingMetricType.MEANING && it !in unsupported }.forEach {
                unsupported[it] = "READ_ALOUD_COPIED_TEXT_IS_NOT_SPONTANEOUS_LANGUAGE_EVIDENCE"
            }
        }
        return unsupported
    }

    fun overall(metrics: List<SpeakingMetricValue>): Int? {
        // 평가 가능한 축의 기존 가중치만 정규화한다. 평가 불가 축은 0점으로 치환하지 않는다.
        var weighted = 0.0
        var availableWeight = 0.0
        for (metric in metrics) {
            if (metric.state != SpeakingMetricState.EVALUATED || metric.score == null) continue
            val weight = weights.getValue(metric.type)
            weighted += metric.score * weight
            availableWeight += weight
        }
        if (availableWeight <= 0) return null
        return (weighted / availableWeight + .5).toInt().coerceIn(0, 100)
    }

    fun requireMetricSet(metrics: List<SpeakingMetricValue>) {
        val types = metrics.map { it.type }
        require(types.size == types.distinct().size) { "SPEAKING_METRIC_DUPLICATE" }
        require(types.toSet() == weights.keys) { "SPEAKING_METRIC_SET_INVALID" }
    }

    private fun rounded(value: Double, places: Int) =
        BigDecimal(value).setScale(places, RoundingMode.HALF_EVEN).toDouble()
}
