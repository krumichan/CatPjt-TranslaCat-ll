package jp.co.translacat.languagelearning.features.leveltest.api.dto

import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelTestDomain
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelTestItemType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class LevelTestItemDetailResponseDto(
    val itemId: Long? = null,
    val questionNumber: Int,
    val domain: LevelTestDomain? = null,
    val itemType: LevelTestItemType? = null,
    val complexityBand: Int,
    val instruction: String? = null,
    val promptText: String? = null,
    val options: List<LevelTestOptionResponseDto> = emptyList(),
    val emphasisText: String? = null,
    val taskGuidance: LevelTestTaskGuidanceResponseDto? = null,
    val selectedOptionKey: String? = null,
    val selectedOptionKeys: List<String> = emptyList(),
    val textAnswer: String? = null,
    val audioSubmitted: Boolean,
    val answerAudioAvailable: Boolean,
    val referenceAudioAvailable: Boolean,
    val transcript: String? = null,
    val recommendedAnswers: List<String> = emptyList(),
    val detailedFeedback: List<JsonObject> = emptyList(),
    val modelAnswerAudioAvailable: Boolean,
    val correctOptionKey: String? = null,
    val correctOrder: List<String> = emptyList(),
    val evaluable: Boolean,
    val score: Int? = null,
    val confidence: Double? = null,
    val metrics: List<JsonObject> = emptyList(),
    val strengths: List<String> = emptyList(),
    val improvements: List<String> = emptyList(),
    val reasonCode: String? = null,
)
