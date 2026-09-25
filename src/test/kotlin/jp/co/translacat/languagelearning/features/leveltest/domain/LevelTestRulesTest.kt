package jp.co.translacat.languagelearning.features.leveltest.domain

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelTestDomain
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelTestItemType
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelAudioPolicy
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelTestRules
import jp.co.translacat.languagelearning.features.leveltest.support.LevelFixtures
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LevelTestRulesTest {
    @Test
    fun `20문항의 영역별 수와 적응 난이도를 보존한다`() {
        assertEquals(
            listOf(3, 3, 4, 4, 3, 3),
            LevelTestDomain.entries.map { d -> LevelTestRules.recipe.count { it.domain == d } },
        )
        assertEquals(3, LevelTestRules.nextBand(2, 100, true)); assertEquals(1, LevelTestRules.nextBand(2, 90, true))
        assertEquals(3, LevelTestRules.nextBand(2, 80, false)); assertEquals(
            2, LevelTestRules.nextBand(2, 55, false),
        ); assertEquals(1, LevelTestRules.nextBand(2, 54, false))
        assertEquals(1, LevelTestRules.nextBand(1, 0, false)); assertEquals(5, LevelTestRules.nextBand(5, 100, false))
    }

    @Test
    fun `문제풀 목표 배분의 합은 정확히 요청 개수다`() {
        for (n in listOf(100, 101, 999, 1000, 1001, 100000)) assertEquals(n, LevelTestRules.poolTargets(n).values.sum())
        assertEquals(20, LevelTestRules.poolTargets(1000)[LevelTestItemType.VOCAB_CONTEXT_CHOICE to 1])
    }

    @Test
    fun `학습 날짜는 UTC가 아닌 사용자 timezone과 DST 경계로 계산한다`() {
        val time = LocalDateTime.parse("2026-03-08T07:30:00")
        val range = LevelTestRules.dayRange(time, "America/New_York")
        assertEquals(23, java.time.Duration.between(range.first, range.second).toHours())
        assertEquals(
            "2026-09-25", LevelTestRules.today(LocalDateTime.parse("2026-09-24T16:00:00"), "Asia/Tokyo").toString(),
        )
    }

    @Test
    fun `문항 누락과 중복 번호로 최종 점수를 만들지 않는다`() {
        assertFailsWith<LevelTestException> { LevelTestRules.domainScores(emptyList(), emptyMap()) }
    }

    @Test
    fun `오디오 확장자 대신 MIME과 실제 컨테이너 서명을 검사한다`() {
        assertEquals("audio/wav", LevelAudioPolicy.validate(LevelFixtures.wav, "audio/wav; codecs=pcm"))
        assertFailsWith<LevelTestException> { LevelAudioPolicy.validate(LevelFixtures.wav, "audio/webm") }
        assertFailsWith<LevelTestException> { LevelAudioPolicy.validate("not-audio".toByteArray(), "audio/wav") }
        assertFailsWith<LevelTestException> { LevelAudioPolicy.validate(byteArrayOf(), "audio/wav") }
    }

    @Test
    fun `영역별 가중 평균은 정수 HALF_UP으로 반올림한다`() {
        val scores = LevelTestDomain.entries.associateWith { if (it == LevelTestDomain.VOCABULARY) 55 else 50 }
        assertEquals(51, LevelTestRules.overall(scores))
        assertEquals("FOUNDATION", LevelTestRules.band(39)); assertEquals(
            "BASIC", LevelTestRules.band(40),
        ); assertEquals("ADVANCED", LevelTestRules.band(85))
    }
}
