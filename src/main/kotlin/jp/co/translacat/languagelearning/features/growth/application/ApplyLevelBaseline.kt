package jp.co.translacat.languagelearning.features.growth.application

import jp.co.translacat.languagelearning.features.growth.domain.exception.GrowthConflict
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthActivity
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthProfile
import jp.co.translacat.languagelearning.features.growth.domain.policy.GrowthPolicy
import jp.co.translacat.languagelearning.features.growth.domain.repository.GrowthRepository
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** LL 기준점 트랜잭션에서 성장 초기값을 반영하고 실측인 경우에만 완료 활동을 저장한다. */
internal class ApplyLevelBaseline(private val records: GrowthRepository) {
    /** 기준점과 보정을 갱신한다. 최초 직접 선택은 점수만 초기화하고 실측 활동·평가 횟수는 추가하지 않는다. */
    fun execute(
        userId: Long, completionId: String, score: Double, date: LocalDate,
        startedAt: LocalDateTime, completedAt: LocalDateTime,
        initializeScores: Boolean = false,
    ) {
        require(userId > 0 && score.isFinite() && score in 0.0..100.0 && !completedAt.isBefore(startedAt))
        val old = records.profile(userId)
        if (old?.baselineCompletionId == completionId) {
            if (old.baseLevelScore != GrowthPolicy.round(score) || old.baselineCompletedAt != completedAt)
                throw GrowthConflict("GROWTH_BASELINE_CONFLICT")
            return
        }
        if (initializeScores && (old?.baselineCompletionId != null || (old?.evaluationCount ?: 0) > 0)) {
            throw GrowthConflict("GROWTH_BASELINE_ALREADY_INITIALIZED")
        }
        if (old?.baselineCompletedAt?.let { !completedAt.isAfter(it) } == true)
            throw GrowthConflict("GROWTH_BASELINE_OUT_OF_ORDER")
        val profile = old ?: GrowthProfile(userId = userId, createdAt = completedAt, updatedAt = completedAt)
        records.saveProfile(
            profile.copy(
                baseLevelScore = GrowthPolicy.round(score), state = "CALIBRATING",
                calibrationStartedDate = date, calibrationCompletedDate = null, baselineCompletionId = completionId,
                baselineCompletedAt = completedAt, updatedAt = completedAt,
                meaningScore = if (initializeScores) score else profile.meaningScore,
                grammarScore = if (initializeScores) score else profile.grammarScore,
                vocabularyScore = if (initializeScores) score else profile.vocabularyScore,
                naturalnessScore = if (initializeScores) score else profile.naturalnessScore,
                expressionScore = if (initializeScores) score else profile.expressionScore,
                reviewPerformance = if (initializeScores) score else profile.reviewPerformance,
                normalPerformance = if (initializeScores) score else profile.normalPerformance,
                challengePerformance = if (initializeScores) score else profile.challengePerformance,
            ),
        )
        // 직접 선택은 학습·응시 실적이 아니므로 신뢰도와 평가 횟수를 그대로 두고 활동을 추가하지 않는다.
        if (initializeScores) return
        val ref = "LL_LEVEL_TEST:$completionId"
        if (records.activity(userId, "LEVEL_TEST", ref) == null) records.saveActivity(
            GrowthActivity(
                userId = userId, source = "LEVEL_TEST", referenceId = ref, learningDate = date,
                title = "Language Level Test",
                durationSeconds = maxOf(0, Duration.between(startedAt, completedAt).seconds),
                status = "COMPLETED", startedAt = startedAt, completedAt = completedAt,
                createdAt = completedAt, updatedAt = completedAt,
            ),
        )
    }
}
