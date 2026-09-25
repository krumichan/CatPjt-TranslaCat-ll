package jp.co.translacat.languagelearning.features.leveltest.api.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class LevelAnswerResultResponseDto(
    val sessionId: Long? = null,
    val itemId: Long? = null,
    val questionNumber: Int,
    val evaluable: Boolean,
    val score: Int? = null,
    val reasonCode: String? = null,
    val completed: Boolean,
    val nextQuestion: LevelQuestionResponseDto? = null,
)
