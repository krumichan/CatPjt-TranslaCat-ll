package jp.co.translacat.languagelearning.features.speaking.application

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeakingRecentFocusTest {
    @Test
    fun `공식 활동 두 근거와 도움 및 음성 지표 예외가 추천 순서를 유지한다`() {
        // 준비: 같은 두 활동의 문법에는 예문 도움 감쇠가 적용되고 발음에는 적용되지 않는다.
        val time = LocalDateTime.parse("2026-09-26T10:00:00")
        val activities = (0..1).map { index ->
            SpeakingFocusActivity(
                time.minusDays(index.toLong()), 1.0, 1.0,
                listOf("SAMPLE_ANSWER"),
                listOf(signal("grammar", "GRAMMAR", "문법 연습"), signal("pronunciation", "PRONUNCIATION", "발음 연습")),
            )
        }

        // 실행
        val result = SpeakingRecentFocus.recommended(emptyList(), activities, 8)

        // 검증: 원본 최소 근거와 metric별 도움 정책에 따라 발음이 먼저 표시된다.
        assertEquals(listOf("발음 연습", "문법 연습"), result)
        assertEquals(emptyList(), SpeakingRecentFocus.recommended(emptyList(), activities.take(1), 8))
    }

    @Test
    fun `Writing을 포함한 최근 삼십 활동 제한은 Speaking 근거 개수에도 적용된다`() {
        // 준비: 원본은 Speaking만 먼저 자르지 않고 Writing 29개와 두 Speaking을 함께 정렬한다.
        val time = LocalDateTime.parse("2026-09-26T10:00:00")
        val writing = (0..28).map { time.minusMinutes(it.toLong()) }
        val speaking = (1..2).map { day ->
            SpeakingFocusActivity(
                time.minusDays(day.toLong()), 1.0, 1.0,
                emptyList(), listOf(signal("grammar", "GRAMMAR", "문법 연습")),
            )
        }

        // 실행 및 검증: 전체 30개 안에는 Speaking 근거 하나만 남아 추천하지 않는다.
        assertEquals(emptyList(), SpeakingRecentFocus.recommended(writing, speaking, 8))
        assertEquals(listOf("문법 연습"), SpeakingRecentFocus.recommended(writing.dropLast(1), speaking, 8))
    }

    private fun signal(pattern: String, metric: String, focus: String) = buildJsonObject {
        put("patternKey", pattern)
        put("metricType", metric)
        put("direction", "WEAKNESS")
        put("confidence", 1.0)
        put("recommendedFocus", focus)
    }
}
