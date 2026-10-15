package jp.co.translacat.languagelearning.features.settings.domain.policy

import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettingsChange
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import jp.co.translacat.languagelearning.support.SettingsFixtures as F

class FeedbackLanguagePolicyTest {
    @Test
    fun `최초 설정의 설명 언어는 지정값 또는 원문 언어를 사용한다`() {
        // 준비
        val base = UserSettingsChange(originLanguage = "ko", learningLanguage = "ja")

        // 실행
        val defaulted = UserSettingsPolicy.change(F.user(), base, F.policy(), F.now)
        val selected = UserSettingsPolicy.change(
            F.user(), base.copy(feedbackLanguage = " en "), F.policy(), F.now,
        )

        // 검증
        assertEquals("ko", defaulted.feedbackLanguage)
        assertEquals("en", selected.feedbackLanguage)
        assertNull(selected.pendingFeedbackLanguage)
        assertNull(selected.pendingEffectiveDate)
    }

    @Test
    fun `설명 언어 변경은 활성 timezone의 다음 학습일에 한 번만 적용한다`() {
        // 준비
        val current = F.configured()

        // 실행: Tokyo 자정 직전과 직후를 나누어 예약 승격을 확인한다.
        val scheduled = UserSettingsPolicy.change(
            current, UserSettingsChange(feedbackLanguage = "en"), F.policy(), F.now,
        )
        val before = UserSettingsPolicy.synchronize(
            scheduled, F.policy(), LocalDateTime.parse("2026-09-24T14:59:59"),
        )
        val after = UserSettingsPolicy.synchronize(
            scheduled, F.policy(), LocalDateTime.parse("2026-09-24T15:00:00"),
        )

        // 검증
        assertEquals("ko", before.feedbackLanguage)
        assertEquals("en", before.pendingFeedbackLanguage)
        assertEquals(LocalDate.of(2026, 9, 25), before.pendingEffectiveDate)
        assertEquals("en", after.feedbackLanguage)
        assertNull(after.pendingFeedbackLanguage)
        assertNull(after.pendingEffectiveDate)
        assertEquals(after, UserSettingsPolicy.synchronize(after, F.policy(), F.now.plusDays(2)))
    }

    @Test
    fun `누락과 null PATCH는 현재 및 예약 설명 언어를 덮어쓰지 않는다`() {
        // 준비
        val pending = F.configured().copy(
            feedbackLanguage = "en", pendingFeedbackLanguage = "ja",
            pendingEffectiveDate = LocalDate.of(2026, 9, 25),
        )

        // 실행
        val unchanged = UserSettingsPolicy.change(pending, UserSettingsChange(), F.policy(), F.now)
        val otherChange = UserSettingsPolicy.change(
            pending, UserSettingsChange(originLanguage = "fr", feedbackLanguage = null), F.policy(), F.now,
        )

        // 검증
        assertEquals(pending, unchanged)
        assertEquals("en", otherChange.feedbackLanguage)
        assertEquals("ja", otherChange.pendingFeedbackLanguage)
        assertEquals("fr", otherChange.pendingOriginLanguage)
    }

    @Test
    fun `기존 미지정 설명 언어는 현재 원문 언어를 보존하고 원문 변경과 독립한다`() {
        // 준비
        val legacy = F.configured().copy(
            feedbackLanguage = null, pendingOriginLanguage = "fr",
            pendingEffectiveDate = LocalDate.of(2026, 9, 25),
        )

        // 실행
        val current = UserSettingsPolicy.synchronize(legacy, F.policy(), F.now)
        val promoted = UserSettingsPolicy.synchronize(legacy, F.policy(), F.now.plusDays(1))

        // 검증
        assertEquals("ko", current.feedbackLanguage)
        assertEquals("fr", promoted.originLanguage)
        assertEquals("ko", promoted.feedbackLanguage)
        assertNull(UserSettingsPolicy.synchronize(F.user(), F.policy(), F.now).feedbackLanguage)
    }

    @Test
    fun `설명 언어는 원문 또는 학습 언어와 같아도 허용한다`() {
        // 준비
        val request = UserSettingsChange(originLanguage = "ko", learningLanguage = "ja")

        // 실행 및 검증
        for (language in listOf("ko", "ja")) {
            val result = UserSettingsPolicy.change(
                F.user(), request.copy(feedbackLanguage = language), F.policy(), F.now,
            )
            assertEquals(language, result.feedbackLanguage)
        }
    }

    @Test
    fun `설명 언어도 비어있거나 코드 길이가 유효하지 않으면 거부한다`() {
        // 준비
        val invalidValues = listOf("", " ", "x", "x".repeat(21))

        // 실행 및 검증
        for (language in invalidValues) {
            val error = assertFailsWith<LearningBusinessException> {
                UserSettingsPolicy.change(
                    F.configured(), UserSettingsChange(feedbackLanguage = language), F.policy(), F.now,
                )
            }
            assertEquals(UserSettingsPolicy.INVALID, error.code)
        }
    }
}
