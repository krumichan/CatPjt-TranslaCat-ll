package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class WritingDiversityPlanGoldenTest {
    @Test
    fun `Python 생성 프롬프트의 선택 키워드 없는 다양성 계획을 재현한다`() {
        val golden = Json.parseToJsonElement(
            requireNotNull(javaClass.getResource("/contracts/writing-generation-python-golden.json")).readText(),
        ).jsonObject
        val request = golden.getValue("request").jsonObject
        val prompt = golden.getValue("prompt").jsonPrimitive.content
        val payload = Json.parseToJsonElement(
            prompt.substringAfter("<learning-data>\n")
                .substringBefore("\n</learning-data>"),
        ).jsonObject
        assertEquals(payload.getValue("writingDiversityPlan"), WritingDiversityPolicy.plan(request).payload())
        for (name in listOf("unconstrainedWindow", "keywordFocused")) {
            val case = golden.getValue(name).jsonObject
            assertEquals(
                case.getValue("plan"), WritingDiversityPolicy.plan(case.getValue("request").jsonObject).payload(),
            )
        }
    }
}
