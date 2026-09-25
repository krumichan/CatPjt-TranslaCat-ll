package jp.co.translacat.languagelearning.features.leveltest.domain.model

import java.time.LocalDate
import java.time.LocalDateTime

internal data class LevelSession(
    val id: Long = 0,
    val uid: String,
    val userId: Long,
    val sessionType: LevelTestSessionType,
    val status: LevelTestSessionStatus = LevelTestSessionStatus.IN_PROGRESS,
    val originLanguage: String,
    val learningLanguage: String,
    val timezone: String,
    val currentQuestionNumber: Int = 1,
    val currentComplexityBand: Int = 2,
    val baseLevelScore: Int? = null,
    val proficiencyBand: String? = null,
    val domainScores: Map<LevelTestDomain, Int> = emptyMap(),
    val startedAt: LocalDateTime,
    val lastActivityAt: LocalDateTime,
    val completedAt: LocalDateTime? = null,
    val completedDate: LocalDate? = null,
    val idempotencyKey: String,
    val operationToken: String? = null,
    val operationKind: String? = null,
    val leaseUntil: LocalDateTime? = null,
) {
    val totalQuestions: Int get() = 20
}

/** 완료 사실의 원본은 LL이다. 전체 Profile 집계와는 별도인 레벨 기준점이다. */
internal data class LevelBaseline(
    val userId: Long,
    val sessionId: Long,
    val completionId: String,
    val sessionType: LevelTestSessionType,
    val score: Int,
    val proficiencyBand: String,
    val completedDate: LocalDate,
    val startedAt: LocalDateTime,
    val completedAt: LocalDateTime,
)
