package jp.co.translacat.languagelearning.features.writing.application

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WritingReportServiceTest {
    @Test
    fun `공식 평가만 통계에 반영하고 답변 수와 이력 상태를 구분한다`() = runBlocking {
        // 준비: 기존 BE의 DAILY 집계와 당일 attempt 기준을 함께 확인한다.
        val date = LocalDate.of(2026, 9, 26)
        val rows = listOf(
            WritingReportEvaluation(
                1, date, date, "DAILY", "SUCCESS", date.atStartOfDay(),
                listOf(80, 90, 70, 80, 60, 50), "{}",
            ),
            WritingReportEvaluation(
                1, date, date, "DAILY", "FAILED", null,
                List(6) { null }, null,
            ),
            WritingReportEvaluation(
                1, date, date.plusDays(1), "REVIEW", "SUCCESS", date.plusDays(1).atStartOfDay(),
                List(6) { 100 }, "{}",
            ),
        )
        val service = WritingReportService { userId ->
            assertEquals(123L, userId)
            WritingReportData(listOf(WritingReportSet(1, date, "FREE", 5, "READY", 2)), rows, 2)
        }

        // 실행
        val report = service.report(123, date, date.minusDays(29), date)

        // 검증: 실패한 답변은 완료 수에 포함하지만 점수 표본에는 성공한 공식 평가만 포함한다.
        assertEquals(2, report.getValue("todayCompleted").jsonPrimitive.int)
        assertEquals(5, report.getValue("todayTotal").jsonPrimitive.int)
        assertEquals(80.0, report.getValue("weeklyAverageScore").jsonPrimitive.double)
        assertEquals(1, report.getValue("evaluations").jsonArray.size)
        val month = report.getValue("monthlyReport").jsonObject
        assertEquals("MEANING", month.getValue("strongestMetric").jsonPrimitive.content)
        assertEquals("EXPRESSION", month.getValue("weakestMetric").jsonPrimitive.content)
        val history = report.getValue("history").jsonArray.single().jsonObject
        assertEquals("WRITING:-1", history.getValue("activityId").jsonPrimitive.content)
        assertEquals("FAILED", history.getValue("evaluationStatus").jsonPrimitive.content)
        assertEquals("FREE", history.getValue("topic").jsonPrimitive.content)
        assertTrue(report.getValue("completedDates").jsonArray.isEmpty())
    }

    @Test
    fun `평가 없는 사용자는 null 평균과 빈 이력을 받는다`() = runBlocking {
        // 준비
        val date = LocalDate.of(2026, 9, 26)
        val service = WritingReportService { WritingReportData(emptyList(), emptyList(), 0) }

        // 실행
        val report = service.report(123, date, date, date)

        // 검증
        assertEquals(JsonNull, report["weeklyAverageScore"])
        assertEquals(JsonNull, report.getValue("monthlyReport").jsonObject["overallAverage"])
        assertEquals(false, report.getValue("started").jsonPrimitive.boolean)
        assertTrue(report.getValue("history").jsonArray.isEmpty())
    }
}
