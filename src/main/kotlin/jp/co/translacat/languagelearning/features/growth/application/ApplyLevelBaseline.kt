package jp.co.translacat.languagelearning.features.growth.application

import jp.co.translacat.languagelearning.features.growth.domain.exception.GrowthConflict
import jp.co.translacat.languagelearning.features.growth.domain.model.*
import jp.co.translacat.languagelearning.features.growth.domain.policy.GrowthPolicy
import jp.co.translacat.languagelearning.features.growth.domain.repository.GrowthRepository
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** LL 레벨 테스트 완료 트랜잭션에 참여한다. Core 호환 수신 테이블은 더 이상 사용하지 않는다. */
internal class ApplyLevelBaseline(private val records: GrowthRepository) {
    fun execute(userId: Long, completionId: String, score: Double, date: LocalDate,
                startedAt: LocalDateTime, completedAt: LocalDateTime) {
        require(userId > 0 && score.isFinite() && score in 0.0..100.0 && !completedAt.isBefore(startedAt))
        val old = records.profile(userId)
        if (old?.baselineCompletionId == completionId) {
            if (old.baseLevelScore != GrowthPolicy.round(score) || old.baselineCompletedAt != completedAt)
                throw GrowthConflict("GROWTH_BASELINE_CONFLICT")
            return
        }
        if (old?.baselineCompletedAt?.let { !completedAt.isAfter(it) } == true)
            throw GrowthConflict("GROWTH_BASELINE_OUT_OF_ORDER")
        val profile = old ?: GrowthProfile(userId = userId, createdAt = completedAt, updatedAt = completedAt)
        records.saveProfile(profile.copy(baseLevelScore = GrowthPolicy.round(score), state = "CALIBRATING",
            calibrationStartedDate = date, calibrationCompletedDate = null, baselineCompletionId = completionId,
            baselineCompletedAt = completedAt, updatedAt = completedAt))
        val ref = "LL_LEVEL_TEST:$completionId"
        if (records.activity(userId, "LEVEL_TEST", ref) == null) records.saveActivity(GrowthActivity(
            userId = userId, source = "LEVEL_TEST", referenceId = ref, learningDate = date,
            title = "Language Level Test", durationSeconds = maxOf(0, Duration.between(startedAt, completedAt).seconds),
            status = "COMPLETED", startedAt = startedAt, completedAt = completedAt,
            createdAt = completedAt, updatedAt = completedAt,
        ))
    }
}
