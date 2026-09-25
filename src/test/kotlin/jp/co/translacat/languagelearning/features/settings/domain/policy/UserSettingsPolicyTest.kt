package jp.co.translacat.languagelearning.features.settings.domain.policy

import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskType.*
import jp.co.translacat.languagelearning.features.settings.domain.model.GoalPolicy
import jp.co.translacat.languagelearning.features.settings.domain.model.InitialSettingsPolicy
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettingsChange
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.*
import jp.co.translacat.languagelearning.support.SettingsFixtures as F

class UserSettingsPolicyTest {
    @Test
    fun `최초 설정의 모든 필드는 즉시 반영된다`() {
        val result = UserSettingsPolicy.change(
            F.user(),
            UserSettingsChange(
                originLanguage = " ko ", learningLanguage = " JA ", timezone = " Europe/Paris ", dailySentenceCount = 9,
                dailySpeakingGoalMinutes = 12, dailyListeningGoalCount = 8, speakingVoiceId = " cedar ",
                speakingPlaybackSpeed = " slow ", defaultListeningTaskTypes = listOf(REPEAT_AFTER_AUDIO, DICTATION),
            ),
            F.policy(), F.now,
        )
        assertEquals("ko", result.originLanguage); assertEquals("JA", result.learningLanguage)
        assertEquals("Europe/Paris", result.timezone); assertEquals(9, result.dailySentenceCount)
        assertEquals(12, result.dailySpeakingGoalMinutes); assertEquals(8, result.dailyListeningGoalCount)
        assertEquals("cedar", result.speakingVoiceId); assertEquals("SLOW", result.speakingPlaybackSpeed)
        assertEquals("[\"DICTATION\",\"REPEAT_AFTER_AUDIO\"]", result.defaultListeningTaskTypesJson)
        assertNull(result.pendingEffectiveDate); assertTrue(result.configured)
    }

    @Test
    fun `첫 설정은 두 언어가 모두 필요하다`() {
        for (request in listOf(
            UserSettingsChange(), UserSettingsChange(originLanguage = "ko"),
            UserSettingsChange(learningLanguage = "ja"),
        )) {
            assertEquals(
                UserSettingsPolicy.NOT_CONFIGURED,
                assertFailsWith<LearningBusinessException> {
                    UserSettingsPolicy.change(F.user(), request, F.policy(), F.now)
                }.code,
            )
        }
    }

    @Test
    fun `기존 사용자 목표와 언어는 내일로 예약하고 음성 속도 Task는 즉시 반영한다`() {
        val current = F.configured()
        val next = UserSettingsPolicy.change(
            current,
            UserSettingsChange(
                originLanguage = "ja", learningLanguage = "en", timezone = "UTC", dailySentenceCount = 8,
                dailySpeakingGoalMinutes = 9, dailyListeningGoalCount = 10, speakingVoiceId = "cedar",
                speakingPlaybackSpeed = "slow", defaultListeningTaskTypes = listOf(SUMMARY),
            ),
            F.policy(), F.now,
        )
        assertEquals("ko", next.originLanguage); assertEquals("ja", next.learningLanguage)
        assertEquals("Asia/Tokyo", next.timezone); assertEquals(5, next.dailySentenceCount)
        assertEquals(5, next.dailySpeakingGoalMinutes); assertEquals(5, next.dailyListeningGoalCount)
        assertEquals("ja", next.pendingOriginLanguage); assertEquals("en", next.pendingLearningLanguage)
        assertEquals("UTC", next.pendingTimezone); assertEquals(8, next.pendingDailySentenceCount)
        assertEquals(9, next.pendingDailySpeakingGoalMinutes); assertEquals(10, next.pendingDailyListeningGoalCount)
        assertEquals(LocalDate.of(2026, 9, 25), next.pendingEffectiveDate)
        assertEquals("cedar", next.speakingVoiceId); assertEquals("SLOW", next.speakingPlaybackSpeed)
        assertEquals("[\"SUMMARY\"]", next.defaultListeningTaskTypesJson)
    }

    @Test
    fun `미래 예약은 승격하지 않고 당일 및 지난 예약은 한 번만 승격한다`() {
        val pending = F.configured().copy(
            pendingLearningLanguage = "en",
            pendingDailySentenceCount = 8,
            pendingDailySpeakingGoalMinutes = 9,
            pendingDailyListeningGoalCount = 7,
            pendingTimezone = "UTC",
            pendingEffectiveDate = LocalDate.of(2026, 9, 25),
        )
        assertEquals(pending, UserSettingsPolicy.synchronize(pending, F.policy(), F.now))
        val midnight = LocalDateTime.parse("2026-09-24T15:00:00")
        val result = UserSettingsPolicy.synchronize(pending, F.policy(), midnight)
        assertEquals("en", result.learningLanguage); assertEquals(8, result.dailySentenceCount)
        assertEquals(9, result.dailySpeakingGoalMinutes); assertEquals(7, result.dailyListeningGoalCount)
        assertEquals("UTC", result.timezone)
        assertNull(result.pendingOriginLanguage); assertNull(result.pendingLearningLanguage); assertNull(
            result.pendingTimezone,
        )
        assertNull(result.pendingDailySentenceCount); assertNull(result.pendingDailySpeakingGoalMinutes)
        assertNull(result.pendingDailyListeningGoalCount); assertNull(result.pendingEffectiveDate)
        assertEquals(result, UserSettingsPolicy.synchronize(result, F.policy(), midnight))
        assertEquals(result, UserSettingsPolicy.synchronize(pending, F.policy(), midnight.plusDays(2)))
    }

    @Test
    fun `예약 timezone으로 미리 자정을 계산하지 않는다`() {
        val pending = F.configured().copy(
            timezone = "America/Los_Angeles",
            pendingTimezone = "Pacific/Kiritimati",
            pendingDailySentenceCount = 8,
            pendingEffectiveDate = LocalDate.of(2026, 9, 25),
        )
        val now = LocalDateTime.parse("2026-09-25T00:30:00")
        assertEquals(pending, UserSettingsPolicy.synchronize(pending, F.policy(), now))
        assertNull(UserSettingsPolicy.synchronize(pending, F.policy(), now.plusHours(7)).pendingEffectiveDate)
    }

    @Test
    fun `잘못된 저장 timezone은 날짜 계산에서만 Tokyo로 대체한다`() {
        val value = F.configured().copy(timezone = "bad-zone")
        assertEquals(
            LocalDate.of(2026, 9, 25),
            UserSettingsPolicy.today(value.timezone, LocalDateTime.parse("2026-09-24T15:00:00")),
        )
        assertEquals("bad-zone", UserSettingsPolicy.synchronize(value, F.policy(), F.now).timezone)
        assertEquals(UserSettingsPolicy.today(null, F.now), UserSettingsPolicy.today("", F.now))
    }

    @Test
    fun `UTC 자정이 아니라 활성 timezone 날짜에서 적용한다`() {
        val current = F.configured().copy(timezone = "America/Los_Angeles")
        val result = UserSettingsPolicy.change(
            current, UserSettingsChange(dailySentenceCount = 7), F.policy(), LocalDateTime.parse("2026-09-24T01:00:00"),
        )
        assertEquals(LocalDate.of(2026, 9, 24), result.pendingEffectiveDate)
    }

    @Test
    fun `여러 PATCH는 누락된 예약값을 유지한다`() {
        val first = UserSettingsPolicy.change(
            F.configured(), UserSettingsChange(learningLanguage = "en", dailySentenceCount = 8), F.policy(), F.now,
        )
        val second = UserSettingsPolicy.change(
            first, UserSettingsChange(dailyListeningGoalCount = 9), F.policy(), F.now.plusMinutes(1),
        )
        assertEquals("en", second.pendingLearningLanguage); assertEquals(8, second.pendingDailySentenceCount)
        assertEquals(9, second.pendingDailyListeningGoalCount)
        assertEquals(first.pendingEffectiveDate, second.pendingEffectiveDate)
    }

    @Test
    fun `음성만 변경하거나 빈 PATCH면 예약 날짜를 갱신하지 않는다`() {
        val current =
            F.configured().copy(pendingDailySentenceCount = 8, pendingEffectiveDate = LocalDate.of(2026, 10, 1))
        assertEquals(current, UserSettingsPolicy.change(current, UserSettingsChange(), F.policy(), F.now))
        val changed =
            UserSettingsPolicy.change(current, UserSettingsChange(speakingVoiceId = "Aoede"), F.policy(), F.now)
        assertEquals(current.pendingEffectiveDate, changed.pendingEffectiveDate)
        assertEquals("Aoede", changed.speakingVoiceId)
    }

    @Test
    fun `같은 목표를 명시해도 원본처럼 내일 예약한다`() {
        val result =
            UserSettingsPolicy.change(F.configured(), UserSettingsChange(dailySentenceCount = 5), F.policy(), F.now)
        assertEquals(5, result.pendingDailySentenceCount); assertNotNull(result.pendingEffectiveDate)
    }

    @Test
    fun `언어쌍은 요청 예약 활성 순서로 판단하고 대소문자를 무시한다`() {
        for (value in listOf("ja", "JA", " ja ")) {
            assertFailsWith<LearningBusinessException> {
                UserSettingsPolicy.change(
                    F.configured(), UserSettingsChange(originLanguage = value), F.policy(), F.now,
                )
            }
        }
        val pending =
            F.configured().copy(pendingLearningLanguage = "en", pendingEffectiveDate = LocalDate.of(2026, 9, 25))
        assertFailsWith<LearningBusinessException> {
            UserSettingsPolicy.change(
                pending, UserSettingsChange(originLanguage = "EN"), F.policy(), F.now,
            )
        }
        assertEquals(
            "ja",
            UserSettingsPolicy.change(
                pending, UserSettingsChange(originLanguage = "ja"), F.policy(), F.now,
            ).pendingOriginLanguage,
        )
    }

    @Test
    fun `부분 손상 언어 상태를 최초 설정으로 잘못 간주하지 않는다`() {
        val current = F.user().copy(originLanguage = "ko")
        val result = UserSettingsPolicy.change(current, UserSettingsChange(learningLanguage = "ja"), F.policy(), F.now)
        assertNull(result.learningLanguage); assertEquals(
            "ja", result.pendingLearningLanguage,
        ); assertFalse(result.configured)
    }

    @Test
    fun `활성값과 미래 예약값 세 종류를 정책 범위로 보정한다`() {
        val current = F.configured().copy(
            dailySentenceCount = 50,
            dailySpeakingGoalMinutes = 1,
            dailyListeningGoalCount = 40,
            pendingDailySentenceCount = 0,
            pendingDailySpeakingGoalMinutes = 100,
            pendingDailyListeningGoalCount = 0,
            pendingEffectiveDate = LocalDate.of(2027, 1, 1),
        )
        val next = UserSettingsPolicy.synchronize(current, F.policy(), F.now)
        assertEquals(20, next.dailySentenceCount); assertEquals(3, next.dailySpeakingGoalMinutes); assertEquals(
            20, next.dailyListeningGoalCount,
        )
        assertEquals(1, next.pendingDailySentenceCount); assertEquals(
            20, next.pendingDailySpeakingGoalMinutes,
        ); assertEquals(1, next.pendingDailyListeningGoalCount)
    }

    @Test
    fun `허용범위 안의 기존 사용자값을 관리자 기본값으로 초기화하지 않는다`() {
        val current =
            F.configured().copy(dailySentenceCount = 10, dailySpeakingGoalMinutes = 15, dailyListeningGoalCount = 8)
        val policy = InitialSettingsPolicy(GoalPolicy(3, 1, 20), GoalPolicy(3, 3, 20), GoalPolicy(2, 1, 20))
        assertEquals(current, UserSettingsPolicy.synchronize(current, policy, F.now))
    }

    @Test
    fun `잘못된 언어 timezone voice speed와 목표는 거부한다`() {
        val cases = listOf(
            UserSettingsChange(originLanguage = "x"),
            UserSettingsChange(learningLanguage = "x".repeat(21)),
            UserSettingsChange(timezone = "Tokyo"),
            UserSettingsChange(timezone = ""),
            UserSettingsChange(speakingVoiceId = " "),
            UserSettingsChange(speakingVoiceId = "a".repeat(101)),
            UserSettingsChange(speakingVoiceId = "\u2003"),
            UserSettingsChange(speakingPlaybackSpeed = "FAST"),
            UserSettingsChange(dailySentenceCount = 0),
            UserSettingsChange(dailySentenceCount = 21),
            UserSettingsChange(dailySpeakingGoalMinutes = 2),
            UserSettingsChange(dailySpeakingGoalMinutes = 21),
            UserSettingsChange(dailyListeningGoalCount = 0),
            UserSettingsChange(dailyListeningGoalCount = 21),
        )
        cases.forEach {
            assertEquals(
                UserSettingsPolicy.INVALID,
                assertFailsWith<LearningBusinessException> {
                    UserSettingsPolicy.change(F.configured(), it, F.policy(), F.now)
                }.code,
            )
        }
        // Java String.isBlank와 Kotlin isBlank의 NBSP 차이까지 원본 규칙에 맞춘다.
        val nbsp =
            UserSettingsPolicy.change(F.configured(), UserSettingsChange(speakingVoiceId = "\u00a0"), F.policy(), F.now)
        assertEquals("\u00a0", nbsp.speakingVoiceId)
    }

    @Test
    fun `목표의 최소 및 최대 경계를 모두 허용한다`() {
        for ((w, s, l) in listOf(Triple(1, 3, 1), Triple(20, 20, 20))) {
            val next = UserSettingsPolicy.change(
                F.configured(),
                UserSettingsChange(dailySentenceCount = w, dailySpeakingGoalMinutes = s, dailyListeningGoalCount = l),
                F.policy(),
                F.now,
            )
            assertEquals(w, next.pendingDailySentenceCount); assertEquals(
                s, next.pendingDailySpeakingGoalMinutes,
            ); assertEquals(l, next.pendingDailyListeningGoalCount)
        }
    }

    @Test
    fun `학습 진입 설정 검사는 양쪽 언어의 null 여부를 따른다`() {
        assertFailsWith<LearningBusinessException> { UserSettingsPolicy.requireConfigured(F.user()) }
        assertFailsWith<LearningBusinessException> {
            UserSettingsPolicy.requireConfigured(
                F.user().copy(originLanguage = "ko"),
            )
        }
        UserSettingsPolicy.requireConfigured(F.configured())
    }

    @Test
    fun `뉴욕 DST 시작과 종료일도 LocalDate 규칙을 유지한다`() {
        assertEquals(
            LocalDate.of(2026, 3, 8),
            UserSettingsPolicy.today("America/New_York", LocalDateTime.parse("2026-03-08T07:30:00")),
        )
        assertEquals(
            LocalDate.of(2026, 11, 1),
            UserSettingsPolicy.today("America/New_York", LocalDateTime.parse("2026-11-01T06:30:00")),
        )
    }
}
