package jp.co.translacat.languagelearning.features.autopractice.application

import jp.co.translacat.languagelearning.features.autopractice.domain.AutoPracticeArea
import jp.co.translacat.languagelearning.features.autopractice.domain.AutoPracticePolicy
import jp.co.translacat.languagelearning.features.settings.domain.model.ConfiguredLanguagePair
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import java.time.Clock
import java.time.LocalDate

/** 활성 자동 출제 유형에서는 기존 진입 경로도 준비된 학습 원본만 조회한다. */
internal class AutoPracticeAccess(private val store: AutoPracticeStore, private val clock: Clock = Clock.systemUTC()) {
    /** null은 수동 생성 경로를 유지한다는 뜻이며, 준비 중이면 빈 배정을 반환한다. */
    suspend fun assigned(
        owner: Long,
        origin: String,
        learning: String,
        area: AutoPracticeArea,
        today: LocalDate,
    ): AutoPracticeAssignment? = store.read {
        val plan = active(owner)?.plan ?: return@read null
        if (!ConfiguredLanguagePair(plan.originLanguage, plan.learningLanguage).matches(origin, learning) ||
            area !in plan.areas
        ) return@read null

        val resources = assignments(owner, origin, learning, today.minusDays(2), today)
            .filter { it.area == area && it.resourceId != null && AutoPracticePolicy.isVisible(it, clock.instant()) }
            .map { LearningPublicId.decode(it.resourceId) }
        AutoPracticeAssignment(resources)
    }

    /** 日付で開く Writing 履歴も、元の生成日ではなく公開回の割り当てを使う。 */
    suspend fun assignedOn(owner: Long, origin: String, learning: String, area: AutoPracticeArea, date: LocalDate): Long? =
        store.read {
            assignments(owner, origin, learning, date, date).firstOrNull {
                it.area == area && it.resourceId != null && AutoPracticePolicy.isVisible(it, clock.instant())
            }?.let { LearningPublicId.decode(it.resourceId) }
        }
}

internal data class AutoPracticeAssignment(val resourceIds: List<Long>)
