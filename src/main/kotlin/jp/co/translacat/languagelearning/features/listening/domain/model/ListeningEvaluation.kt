package jp.co.translacat.languagelearning.features.listening.domain.model

import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningAlignmentEntry
import kotlinx.serialization.Serializable

@Serializable
internal data class ListeningAssistanceUsage(val type: String, val count: Int = 1)

@Serializable
internal data class ListeningEvidence(
    val metric: String,
    val feedback: String,
    val severity: String = "INFO",
    val startMs: Int? = null,
    val endMs: Int? = null,
    val reference: String? = null,
    val recognized: String? = null,
)

@Serializable
internal data class ListeningMetric(
    val type: String,
    val score: Double?,
    val weight: Double,
    val confidence: Double,
    val evidence: List<ListeningEvidence> = emptyList(),
    val state: String = "EVALUATED",
    val notEvaluableReason: String? = null,
)

@Serializable
internal data class ListeningProfileSignal(
    val metric: String,
    val score: Double,
    val confidence: Double,
    val evidenceWeight: Double,
    val evidenceIds: List<String>,
    val sourceTask: ListeningTaskType,
    val policyVersion: String = "listening-profile",
)

@Serializable
internal data class ListeningTaskResult(
    val taskType: ListeningTaskType,
    val status: String,
    val evaluable: Boolean,
    val score: Int? = null,
    val confidence: Double? = null,
    val reasonCode: String? = null,
    val assistanceLevel: String = "INDEPENDENT",
    val metrics: List<ListeningMetric> = emptyList(),
    val alignment: List<ListeningAlignmentEntry> = emptyList(),
    val evidence: List<ListeningEvidence> = emptyList(),
    val strengths: List<String> = emptyList(),
    val improvements: List<String> = emptyList(),
    val recommendedInterpretations: List<String> = emptyList(),
    val deliveredMeaningUnits: List<String> = emptyList(),
    val omittedMeaningUnits: List<String> = emptyList(),
    val misunderstoodMeaningUnits: List<String> = emptyList(),
    val addedInformation: List<String> = emptyList(),
    val profileSignals: List<ListeningProfileSignal> = emptyList(),
    val profileEligible: Boolean = false,
    val assistanceUsage: List<ListeningAssistanceUsage> = emptyList(),
)

internal data class ListeningEvaluationContext(
    val official: Boolean = true,
    val answerRevealed: Boolean = false,
    val assistance: List<ListeningAssistanceUsage> = emptyList(),
) {
    val revealed get() = answerRevealed || assistance.any { it.type == "SHOW_ANSWER" }
}
