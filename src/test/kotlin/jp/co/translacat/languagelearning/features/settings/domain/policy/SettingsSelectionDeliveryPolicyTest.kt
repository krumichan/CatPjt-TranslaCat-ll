package jp.co.translacat.languagelearning.features.settings.domain.policy

import jp.co.translacat.languagelearning.features.settings.domain.model.SelectionDeliveryStatus.*
import jp.co.translacat.languagelearning.features.settings.domain.model.SettingsSelectionDelivery
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SettingsSelectionDeliveryPolicyTest {
    private val base = LocalDateTime.parse("2026-09-24T01:00:00.123456")

    @Test
    fun `동일 revision의 첫 전달을 수락한다`() {
        assertEquals(APPLIED, SettingsSelectionDeliveryPolicy.decide(10, base, base, null))
    }

    @Test
    fun `이미 처리한 이벤트와 역순 이벤트를 다시 적용하지 않는다`() {
        val last = SettingsSelectionDelivery(123, 10, base, base.plusNanos(1000))
        for (id in listOf(9L, 10L)) assertEquals(
            DUPLICATE, SettingsSelectionDeliveryPolicy.decide(id, base, base.plusNanos(1000), last),
        )
    }

    @Test
    fun `직접 PATCH 뒤의 이전 revision은 거부한다`() {
        assertEquals(SUPERSEDED, SettingsSelectionDeliveryPolicy.decide(11, base, base.plusNanos(1000), null))
    }

    @Test
    fun `동일 base에서 발생한 후속 세션은 수신 순서와 무관하게 큰 ID로 결정한다`() {
        val applied = base.plusNanos(1000)
        val last = SettingsSelectionDelivery(123, 10, base, applied)
        assertEquals(APPLIED, SettingsSelectionDeliveryPolicy.decide(11, base, applied, last))
    }

    @Test
    fun `수신 후 직접 PATCH가 있으면 같은 base라도 거부한다`() {
        val last = SettingsSelectionDelivery(123, 10, base, base.plusNanos(1000))
        assertEquals(SUPERSEDED, SettingsSelectionDeliveryPolicy.decide(11, base, base.plusNanos(2000), last))
    }

    @Test
    fun `새 revision으로 발생한 세션은 다시 수락한다`() {
        val current = base.plusNanos(2000)
        val last = SettingsSelectionDelivery(123, 10, base, null)
        assertEquals(APPLIED, SettingsSelectionDeliveryPolicy.decide(11, current, current, last))
    }

    @Test
    fun `무효화된 전달의 뒤를 같은 base로 이어갈 수 없다`() {
        val last = SettingsSelectionDelivery(123, 10, base, null)
        assertEquals(SUPERSEDED, SettingsSelectionDeliveryPolicy.decide(11, base, base.plusNanos(1000), last))
    }

    @Test
    fun `이벤트 ID는 양수여야 한다`() {
        assertFailsWith<IllegalArgumentException> { SettingsSelectionDeliveryPolicy.decide(0, base, base, null) }
    }
}
