package jp.co.translacat.languagelearning.features.settings.api

import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import jp.co.translacat.languagelearning.support.SettingsFixtures as F

class FeedbackLanguageDtoMapperTest {
    @Test
    fun `응답은 현재 설명 언어와 다음날 예약 설명 언어를 각각 유지한다`() {
        // 준비
        val result = UserSettingsResult(
            F.configured().copy(feedbackLanguage = "en", pendingFeedbackLanguage = "fr"), F.policy(),
        )

        // 실행
        val response = result.toResponse()

        // 검증
        assertEquals("en", response.feedbackLanguage)
        assertEquals("fr", response.pendingFeedbackLanguage)
        assertEquals("ko", response.originLanguage)
        assertEquals("ja", response.learningLanguage)
    }

    @Test
    fun `기존 설정의 누락된 설명 언어는 원문 언어로 응답하고 미설정 사용자는 null이다`() {
        // 준비
        val legacy = UserSettingsResult(F.configured().copy(feedbackLanguage = null), F.policy())
        val unconfigured = UserSettingsResult(F.user(), F.policy())

        // 실행
        val oldResponse = legacy.toResponse()
        val newResponse = unconfigured.toResponse()

        // 검증
        assertEquals("ko", oldResponse.feedbackLanguage)
        assertNull(oldResponse.pendingFeedbackLanguage)
        assertNull(newResponse.feedbackLanguage)
    }
}
