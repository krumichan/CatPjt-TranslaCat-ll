package jp.co.translacat.languagelearning.features.overview

import jp.co.translacat.languagelearning.features.overview.application.HistoryProjection
import kotlinx.serialization.json.*
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryProjectionTest {
    @Test
    fun `원본 기간 파싱과 어제까지의 streak 및 출처 enum 순서를 보존한다`() {
        // 준비: 오늘 공백과 이전의 더 긴 연속 구간, 같은 날 역순으로 입력된 두 출처를 만든다.
        val today = LocalDate.parse("2026-09-27")
        val dates = listOf(1L, 2L, 4L, 5L, 6L).map { today.minusDays(it) }.toSet()
        fun item(source: String) = buildJsonObject {
            put("source", source); put("learningDate", today.toString()); put("completionStatus", "COMPLETED"); put(
            "evaluationStatus", "SUCCESS",
        )
        }

        // 실행
        val streak = HistoryProjection.streak(dates, today)
        val history = HistoryProjection.history(listOf(item("VOCABULARY"), item("WRITING")), "success")

        // 검증
        assertEquals(Json.parseToJsonElement("""{"current":2,"longest":3,"lastStudyDate":"2026-09-26"}"""), streak)
        assertEquals(
            listOf("WRITING", "VOCABULARY"), history.map { it.jsonObject.getValue("source").jsonPrimitive.content },
        )
        assertEquals(
            listOf(30, 30, 1, 365, 3), listOf(null, "garbage", "0d", "999d", " 3D ").map(HistoryProjection::days),
        )
    }
}
