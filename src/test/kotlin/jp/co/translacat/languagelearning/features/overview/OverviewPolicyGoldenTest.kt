package jp.co.translacat.languagelearning.features.overview

import jp.co.translacat.languagelearning.features.overview.application.OverviewFacts
import jp.co.translacat.languagelearning.features.overview.application.RecentInsightProjection
import jp.co.translacat.languagelearning.features.overview.application.SourceTrendProjection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class OverviewPolicyGoldenTest {
    @Test
    fun `Java 원본 35개 출처별 추세와 최근 근거를 동일하게 계산한다`() {
        // 준비: 기존 Java 실제 정책에서 추출한 합성 출력은 수정하지 않는다.
        val cases = Json.parseToJsonElement(
            checkNotNull(javaClass.getResource("/contracts/overview-policy-be-golden.json")).readText(),
        ).jsonArray
        for (element in cases) {
            val row = element.jsonObject
            val facts = OverviewFacts(
                row.getValue("writing").jsonObject, row.getValue("speaking").jsonObject,
                row.getValue("speakingGrowth").jsonArray.map { it.jsonObject }, row.getValue("listening").jsonObject,
                row.getValue("practice").jsonObject,
            )
            for ((name, expected) in row.getValue("expected").jsonObject) {
                val source = name.takeUnless { it == "ALL" }

                // 실행: 활동 30개 창·출처별 가중치·출처 최소 근거 정책을 직접 실행한다.
                val trend = SourceTrendProjection.project(
                    facts, source, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"),
                )
                val insights = RecentInsightProjection.project(
                    facts.writing, facts.speaking,
                    row.getValue("listeningDashboard").jsonObject.getValue("metrics").jsonArray, source, 30,
                )

                // 검증: 수치와 출처 순서·중복 제거·추천 문구를 원본 전체 JSON과 대조한다.
                val context = "writing=${row["writingCount"]}, source=$name"
                assertEquals(expected.jsonObject.getValue("trend"), trend, context)
                assertEquals(expected.jsonObject.getValue("insights"), insights, context)
            }
        }
    }
}
