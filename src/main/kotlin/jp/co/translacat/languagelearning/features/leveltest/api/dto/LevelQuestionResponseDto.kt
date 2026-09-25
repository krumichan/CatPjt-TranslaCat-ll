package jp.co.translacat.languagelearning.features.leveltest.api.dto

import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import kotlinx.serialization.Serializable

@Serializable
internal data class LevelQuestionResponseDto(
    val sessionId: Long? = null,
    val sessionType: LevelTestSessionType? = null,
    val itemId: Long? = null,
    val questionNumber: Int,
    val totalQuestions: Int,
    val domain: LevelTestDomain? = null,
    val itemType: LevelTestItemType? = null,
    val complexityBand: Int,
    val instruction: String? = null,
    val instructionLanguage: String? = null,
    val answerMode: LevelTestAnswerMode? = null,
    val answerLanguage: String? = null,
    val promptText: String? = null,
    val options: List<LevelTestOptionResponseDto> = emptyList(),
    val emphasisText: String? = null,
    val taskGuidance: LevelTestTaskGuidanceResponseDto? = null,
    val referenceAudioAvailable: Boolean,
    val repeatReferenceText: String? = null,
    val referencePlaybackLimit: Int? = null,
    val maxAnswerLength: Int? = null,
    val maxAudioSeconds: Int? = null,
    val status: LevelTestItemStatus? = null,
    val evaluationReasonCode: String? = null,
)
