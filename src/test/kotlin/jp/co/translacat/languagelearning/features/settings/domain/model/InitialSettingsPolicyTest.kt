package jp.co.translacat.languagelearning.features.settings.domain.model

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InitialSettingsPolicyTest {
    @Test
    fun `상하한과 같은 기본값은 유효하다`() {
        assertEquals(1, GoalPolicy(1, 1, 20).defaultValue)
        assertEquals(20, GoalPolicy(20, 1, 20).defaultValue)
        assertEquals(5, GoalPolicy(5, 5, 5).defaultValue)
    }

    @Test
    fun `잘못된 정책 범위는 값을 보정하지 않고 실패한다`() {
        for ((default, minimum, maximum) in listOf(Triple(0, 0, 20), Triple(5, 10, 3), Triple(21, 1, 20), Triple(1, 3, 20))) {
            assertFailsWith<IllegalArgumentException> { GoalPolicy(default, minimum, maximum) }
        }
    }

    @Test
    fun `신규 설정은 세 가지 정책 기본값을 각각 사용한다`() {
        val now = LocalDateTime.of(2026, 9, 24, 0, 0)
        val policy = InitialSettingsPolicy(GoalPolicy(3, 1, 20), GoalPolicy(4, 3, 20), GoalPolicy(6, 1, 20))
        val value = NewUserSettings.fromPolicy(100, policy, now)
        assertEquals(3, value.dailySentenceCount)
        assertEquals(4, value.dailySpeakingGoalMinutes)
        assertEquals(6, value.dailyListeningGoalCount)
        assertEquals(now, value.nowUtc)
    }

    @Test
    fun `저장 입력의 비정상 식별자와 목표는 거부한다`() {
        val now = LocalDateTime.of(2026, 9, 24, 0, 0)
        assertFailsWith<IllegalArgumentException> { NewUserSettings(0, 5, 5, 5, now) }
        assertFailsWith<IllegalArgumentException> { NewUserSettings(1, 0, 5, 5, now) }
        assertFailsWith<IllegalArgumentException> { NewUserSettings(1, 5, -1, 5, now) }
    }
}
