package jp.co.translacat.languagelearning.features.overview

import jp.co.translacat.languagelearning.features.overview.application.*
import jp.co.translacat.languagelearning.features.webaiplan.domain.model.*
import kotlinx.serialization.json.*
import java.time.LocalDate
import kotlin.test.*

class WebAiOverviewProjectionTest {
    private val date = LocalDate.parse("2026-10-15")

    @Test
    fun `동일 분야의 내부 검증 점수는 출처 비율 없이 기존 최근성 가중치로 합친다`() {
        // 준비: 원래 Writing 80점과 Web AI 내부 검증 20점을 같은 날짜에 둔다.
        val writing = buildJsonObject {
            put("evaluations", buildJsonArray {
                add(buildJsonObject {
                    put("learningDate", date.toString())
                    put("evaluatedAt", "2026-10-15T20:00:00")
                    listOf("meaning", "grammar", "vocabulary", "naturalness", "expression").forEach {
                        put("${it}Score", 80.0)
                    }
                })
            })
        }
        val webAi = WebAiOverviewProjection.facts(listOf(result()), date, date)
        val facts = OverviewFacts(writing, emptyJson(), emptyList(), emptyJson(), emptyJson(), webAi)

        // 실행
        val merged = SourceTrendProjection.project(facts, "WRITING", date, date)
        val externalOnly = SourceTrendProjection.project(facts, "WEB_AI", date, date)

        // 검증: 신규 출처 자체에 임의 20%/80% 비중을 부여하지 않는다.
        assertEquals(2, merged.getValue("sampleCount").jsonPrimitive.int)
        assertEquals(60.0, metric(merged, "MEANING"))
        assertEquals(20.0, metric(externalOnly, "MEANING"))
        assertEquals(1, externalOnly.getValue("sampleCount").jsonPrimitive.int)
    }

    @Test
    fun `코칭과 외부 점수는 능력 점수에서 제외하고 원래 이력 출처와 날짜를 보존한다`() {
        // 준비: 사용자 시간대의 학습일은 UTC 저장일과 다를 수 있다.
        val verified = result()
        val coaching = result().copy(
            id = "coaching", items = listOf(
                WebAiVerifiedItem(
                    item = submitted("SPEAKING_FREE").copy(externalScore = 100.0),
                    status = "COACHING", reason = "AUDIO_EVIDENCE_REQUIRED",
                ),
            ),
        )

        // 실행
        val facts = WebAiOverviewProjection.facts(listOf(verified, coaching), date, date)
        val history = WebAiOverviewProjection.history(coaching)

        // 검증
        assertEquals(1, facts.size)
        assertEquals("WEB_AI:coaching", history.getValue("activityId").jsonPrimitive.content)
        assertEquals("WEB_AI", history.getValue("source").jsonPrimitive.content)
        assertEquals(JsonNull, history.getValue("overallScore"))
        assertEquals(date.toString(), history.getValue("learningDate").jsonPrimitive.content)
        assertEquals(setOf(date), WebAiOverviewProjection.completedDates(listOf(coaching)))
        assertEquals(0, WebAiOverviewProjection.completedItems(listOf(coaching), "WRITING", date))
        assertTrue(WebAiOverviewProjection.includesDomain(coaching, "SPEAKING"))
    }

    @Test
    fun `읽기 검증 지표도 현재 기간과 기존 CONTENT 지표에 함께 반영한다`() {
        // 준비
        val verified = result().copy(items = listOf(
            WebAiVerifiedItem(
                submitted("READING_COMPREHENSION"), "VERIFIED", 40.0,
                metrics = listOf(WebAiMetric("CONTENT", 40.0)),
            ),
        ))
        val practice = buildJsonObject {
            put("sets", buildJsonArray {
                add(buildJsonObject {
                    put("domain", "READING"); put("status", "COMPLETED"); put("learningDate", date.toString())
                    put("metrics", buildJsonArray {
                        add(buildJsonObject { put("skillTag", "CONTENT"); put("score", 100.0); put("sampleCount", 1) })
                    })
                })
            })
        }
        val facts = OverviewFacts(emptyJson(), emptyJson(), emptyList(), emptyJson(), practice,
            WebAiOverviewProjection.facts(listOf(verified), date, date))

        // 실행
        val trend = SourceTrendProjection.project(facts, "READING", date, date)
        val nextDay = SourceTrendProjection.project(facts, "WEB_AI", date.plusDays(1), date.plusDays(1))

        // 검증
        assertEquals(80.0, metric(trend, "CONTENT"))
        assertEquals(2, trend.getValue("sampleCount").jsonPrimitive.int)
        assertEquals(0, nextDay.getValue("sampleCount").jsonPrimitive.int)
    }

    private fun result() = WebAiResult(
        id = "synthetic", runId = "run", userId = 123, status = "VERIFIED",
        originalFeedbackMarkdown = "# Original external feedback", items = listOf(
            WebAiVerifiedItem(submitted("WRITING_TRANSLATION"), "VERIFIED", 20.0,
                metrics = listOf(WebAiMetric("MEANING", 20.0))),
        ),
        createdAt = "2026-10-14T23:30:00Z", updatedAt = "2026-10-15T19:00:00Z",
        originLanguage = "ko", learningLanguage = "ja", feedbackLanguage = "ko",
        learningDate = date.toString(), planName = "Synthetic plan",
    )
    private fun submitted(area: String) = WebAiSubmittedItem(
        "$area:STANDARD:1", area, "STANDARD", "Synthetic task", "Synthetic answer", externalScore = 100.0,
    )
    private fun metric(trend: JsonObject, code: String) = trend.getValue("metrics").jsonObject.getValue(code)
        .jsonArray.single().jsonObject.getValue("score").jsonPrimitive.double
    private fun emptyJson() = buildJsonObject {}
}
