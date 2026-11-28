package jp.co.translacat.languagelearning.features.autopractice.application

import jp.co.translacat.languagelearning.features.autopractice.domain.AutoPracticeArea
import jp.co.translacat.languagelearning.features.settings.domain.model.ConfiguredLanguagePair
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import java.time.LocalDate

/** 활성 자동 출제 유형에서는 기존 진입 경로도 준비된 학습 원본만 조회한다. */
internal class AutoPracticeAccess(private val store: AutoPracticeStore) {
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
            .filter { it.area == area && it.resourceId != null }
            .map { LearningPublicId.decode(it.resourceId) }
        AutoPracticeAssignment(resources)
    }
}

internal data class AutoPracticeAssignment(val resourceIds: List<Long>)
