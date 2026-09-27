package jp.co.translacat.languagelearning.features.overview

import jp.co.translacat.languagelearning.features.overview.application.DashboardProjection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class DashboardProjectionGoldenTest {
    @Test
    fun `기존 BE 대시보드 정책 전체 응답 여덟 사례와 일치한다`() {
        // 준비: 기존 Java 업무 정책이 생성한 새 합성 golden을 읽는다.
        val cases = Json.parseToJsonElement(
            checkNotNull(javaClass.getResourceAsStream("/contracts/dashboard-projection-be-golden.json"))
                .bufferedReader().use { it.readText() },
        ).jsonArray

        // 실행 및 검증: 성장 임계값·근거 개수·표시 순서·그룹·반올림을 전체 JSON으로 대조한다.
        cases.forEach { raw ->
            val case = raw.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val listening = case.getValue("kind").jsonPrimitive.content == "listening"
            val ability = if (listening) DashboardProjection.listeningAbility(case.getValue("profiles").jsonArray)
            else DashboardProjection.ability(case.getValue("request").jsonObject)
            val growth = if (listening) DashboardProjection.listeningGrowth(case.getValue("trends").jsonArray)
            else DashboardProjection.growth(case.getValue("request").jsonObject)
            assertEquals(case.getValue("ability"), ability, name)
            assertEquals(case.getValue("growth"), growth, name)
        }
        assertEquals(8, cases.size)
    }
}
