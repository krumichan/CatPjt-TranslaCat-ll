package jp.co.translacat.languagelearning.features.growth.domain.model

import java.time.LocalDate
import java.time.LocalDateTime

/** 사용자 계정은 BE 소유다. LL에는 검증된 외부 userId만 보관한다. */
internal data class GrowthProfile(
    val userId: Long,
    val profileVersion: String = "PROFILE",
    val state: String = "LEVEL_TEST_REQUIRED",
    val baseLevelScore: Double? = null,
    val calibrationStartedDate: LocalDate? = null,
    val calibrationCompletedDate: LocalDate? = null,
    val meaningScore: Double? = null,
    val grammarScore: Double? = null,
    val vocabularyScore: Double? = null,
    val naturalnessScore: Double? = null,
    val expressionScore: Double? = null,
    val reviewPerformance: Double? = null,
    val normalPerformance: Double? = null,
    val challengePerformance: Double? = null,
    val evaluationCount: Int = 0,
    val confidence: Double = 0.0,
    val trend: String = "stable",
    val additionalSignalsJson: String = "{}",
    val baselineCompletionId: String? = null,
    val baselineCompletedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)
