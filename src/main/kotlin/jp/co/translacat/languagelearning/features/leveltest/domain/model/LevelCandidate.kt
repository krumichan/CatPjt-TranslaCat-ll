package jp.co.translacat.languagelearning.features.leveltest.domain.model

import java.time.LocalDateTime

internal data class LevelCandidate(
    val id: Long = 0,
    val sessionId: Long,
    val questionNumber: Int,
    val band: Int,
    val status: String = "PENDING",
    val token: String? = null,
    val leaseUntil: LocalDateTime? = null,
    val attempt: Int = 0,
    val poolQuestionId: Long? = null,
    val reason: String? = null,
    val createdAt: LocalDateTime,
)

internal data class LevelPoolQuestion(
    val id: Long = 0,
    val originLanguage: String,
    val learningLanguage: String,
    val data: LevelQuestionData,
    val active: Boolean = true,
    val quarantineReason: String? = null,
    val createdAt: LocalDateTime,
)
