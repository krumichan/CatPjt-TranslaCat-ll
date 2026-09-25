package jp.co.translacat.languagelearning.features.growth.application

import jp.co.translacat.languagelearning.features.growth.domain.exception.GrowthConflict
import jp.co.translacat.languagelearning.features.growth.domain.model.*
import jp.co.translacat.languagelearning.features.growth.domain.policy.GrowthPolicy
import jp.co.translacat.languagelearning.features.growth.domain.repository.GrowthRepository
import java.time.LocalDateTime

/** 한 Core 커밋의 모든 명령을 같은 LL 트랜잭션에서 처리한다. 원격 호출은 없다. */
internal class GrowthProjector(private val records: GrowthRepository) {
    fun apply(userId: Long, change: GrowthChange, now: LocalDateTime) {
        when (change) {
            is GrowthChange.LearningPrepared -> {
                val profile = records.profile(userId) ?: throw GrowthConflict("LEVEL_TEST_REQUIRED")
                if (profile.state == "LEVEL_TEST_REQUIRED") throw GrowthConflict("LEVEL_TEST_REQUIRED")
                records.saveProfile(GrowthPolicy.prepare(profile, change.learningDate, now))
            }
            is GrowthChange.WritingScored -> {
                val old = records.profile(userId) ?: GrowthProfile(userId = userId, createdAt = now, updatedAt = now)
                val ready = GrowthPolicy.prepare(old, change.learningDate, now)
                records.saveProfile(GrowthPolicy.applyScores(ready, change, now))
                change.signals.forEach { (type, values) -> signals(userId, type, values, now) }
                val score = (change.scores[0] + change.scores[2]) / 2.0
                // 원본처럼 동일 key가 여러 번 선택되었다면 각 선택을 반영한다. 전달 재시도는 inbox에서 제거한다.
                change.canonicalKeys.forEach { key ->
                    val mastery = records.mastery(userId, key) ?: KeywordMastery(userId, key, createdAt = now, updatedAt = now)
                    records.saveMastery(GrowthPolicy.scored(mastery, score, GrowthPolicy.weight(ready), now))
                }
            }
            is GrowthChange.SignalsTouched -> signals(userId, change.type, change.values, now)
            is GrowthChange.KeywordsSelected -> change.canonicalKeys.forEach { key ->
                val mastery = records.mastery(userId, key) ?: KeywordMastery(userId, key, createdAt = now, updatedAt = now)
                records.saveMastery(GrowthPolicy.selected(mastery, change.learningDate, now))
            }
            is GrowthChange.ActivityRecorded -> recordActivity(userId, change.activity, change.metrics, now)
            is GrowthChange.SpeakingScored -> {
                // SESSION_COACHING은 API 계약부터 거부한다. 진단/코칭을 공식 metric/evidence로 승격하지 않는다.
                recordActivity(userId, change.activity, if (change.formal) change.metrics else null, now)
                if (change.formal) change.evidence.forEach { fact ->
                    if (fact.patternKey.isNotBlank()) {
                        val key = GrowthPolicy.signalKey(fact.patternKey)
                        val direction = GrowthPolicy.direction(fact.direction)
                        val previous = records.evidence(userId, "SPEAKING", key, direction)
                        records.saveEvidence(previous?.let { GrowthPolicy.touched(it, fact, change.activityWeight, now) }
                            ?: GrowthEvidence(userId, "SPEAKING", fact.metricType, key, direction, 1,
                                change.activityWeight, fact.confidence, fact.recommendedFocus, now, now, now))
                    }
                }
            }
        }
    }

    private fun signals(userId: Long, type: String, values: List<String>, now: LocalDateTime) {
        require(type in GrowthPolicy.signalTypes)
        values.filter { it.isNotBlank() }.forEach { raw ->
            val key = GrowthPolicy.signalKey(raw)
            val old = records.signal(userId, type, key)
            records.saveSignal(old?.copy(occurrenceCount = Math.addExact(old.occurrenceCount, 1), lastSeenAt = now, updatedAt = now)
                ?: GrowthSignal(userId, type, key, 1, now, now, now))
        }
    }

    private fun recordActivity(userId: Long, fact: GrowthActivity, metrics: List<GrowthMetric>?, now: LocalDateTime) {
        require(userId == fact.userId)
        val old = records.activity(userId, fact.source, fact.referenceId)
        if (old != null && (old.learningDate != fact.learningDate || java.time.Duration.between(old.startedAt, fact.startedAt).abs() > java.time.Duration.ofNanos(1000))) {
            throw GrowthConflict("GROWTH_ACTIVITY_IDENTITY_CONFLICT")
        }
        // Core JDBC의 DATETIME(6) 반올림/절삭 차이 1μs만 허용하고 최초 시각은 보존한다.
        // 확정 결과가 지연된 상태 이벤트 때문에 진행 중/실패로 돌아가면 안 된다.
        if (old?.status in setOf("EVALUATED", "INSUFFICIENT_EVIDENCE") && fact.status != old?.status) {
            throw GrowthConflict("GROWTH_ACTIVITY_FINALIZED")
        }
        val saved = records.saveActivity(fact.copy(id = old?.id ?: 0, startedAt = old?.startedAt ?: fact.startedAt, createdAt = old?.createdAt ?: now, updatedAt = now))
        if (metrics != null) records.replaceMetrics(saved.id, metrics)
    }
}
