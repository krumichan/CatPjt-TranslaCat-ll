package jp.co.translacat.languagelearning.features.settings.domain.policy

import jp.co.translacat.languagelearning.features.settings.domain.model.SelectionDeliveryStatus
import jp.co.translacat.languagelearning.features.settings.domain.model.SettingsSelectionDelivery
import java.time.LocalDateTime

/** 직접 PATCH가 만든 새 revision을 오래된 outbox가 덮어쓰지 못하게 한다. */
internal object SettingsSelectionDeliveryPolicy {
    fun decide(
        eventId: Long,
        expectedRevision: LocalDateTime,
        currentRevision: LocalDateTime,
        last: SettingsSelectionDelivery?,
    ): SelectionDeliveryStatus {
        require(eventId > 0)
        if (last != null && eventId <= last.lastEventId) return SelectionDeliveryStatus.DUPLICATE
        if (expectedRevision == currentRevision) return SelectionDeliveryStatus.APPLIED
        // 같은 revision을 보고 연속 생성한 세션은 source event 순서로 최종 선택을 결정한다.
        if (last != null && last.appliedRevision == currentRevision && last.baseRevision == expectedRevision) {
            return SelectionDeliveryStatus.APPLIED
        }
        return SelectionDeliveryStatus.SUPERSEDED
    }
}
