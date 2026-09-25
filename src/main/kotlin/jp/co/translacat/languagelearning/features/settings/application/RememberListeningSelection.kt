package jp.co.translacat.languagelearning.features.settings.application

import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskType
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningTaskSelectionPolicy
import jp.co.translacat.languagelearning.features.settings.domain.model.SelectionDeliveryStatus
import jp.co.translacat.languagelearning.features.settings.domain.model.SettingsSelectionDelivery
import jp.co.translacat.languagelearning.features.settings.domain.policy.SettingsSelectionDeliveryPolicy
import jp.co.translacat.languagelearning.features.settings.domain.policy.UserSettingsPolicy
import java.time.LocalDateTime

internal class RememberListeningSelection(private val unitOfWork: SelectionSettingsUnitOfWork) {
    suspend fun execute(
        userId: Long, eventId: Long, expectedRevision: LocalDateTime, taskTypes: List<ListeningTaskType?>,
    ): SelectionDeliveryStatus {
        require(userId > 0 && eventId > 0)
        require(
            expectedRevision.nano % 1000 == 0 && expectedRevision.year in 1000..9999,
        ) { "설정 revision은 마이크로초 정밀도여야 합니다." }
        val canonical = ListeningTaskSelectionPolicy.toCanonicalJson(taskTypes)
        return unitOfWork.execute selection@{
            val current = loadCurrentUserSettings(userId).settings
            UserSettingsPolicy.requireConfigured(current)
            val last = deliveries.findForUser(userId)
            val decision = SettingsSelectionDeliveryPolicy.decide(eventId, expectedRevision, current.updatedAt, last)
            if (decision == SelectionDeliveryStatus.DUPLICATE) return@selection decision
            val saved = if (decision == SelectionDeliveryStatus.APPLIED) {
                // 값이 같아도 이벤트의 revision을 구분한다. 그 뒤의 직접 PATCH와 순서를 비교하기 위해 필요하다.
                saveIfChanged(
                    current,
                    current.copy(
                        defaultListeningTaskTypesJson = canonical,
                        updatedAt = maxOf(nowUtc, current.updatedAt.plusNanos(1000)),
                    ),
                )
            } else null
            deliveries.save(SettingsSelectionDelivery(userId, eventId, expectedRevision, saved?.updatedAt))
            decision
        }
    }
}
