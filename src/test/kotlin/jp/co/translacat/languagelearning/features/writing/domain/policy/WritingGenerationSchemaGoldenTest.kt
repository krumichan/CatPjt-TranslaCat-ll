package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class WritingGenerationSchemaGoldenTest {
    @Test
    fun `후보 Schema는 무키워드 키워드 범위 번역 script에서 Python과 같다`() {
        val golden = Json.parseToJsonElement(
            requireNotNull(javaClass.getResource("/contracts/writing-generation-python-golden.json")).readText(),
        ).jsonObject
        for (name in listOf("baseline", "unconstrainedWindow", "keywordFocused", "translation")) {
            val case = if (name == "baseline") golden else golden.getValue(name).jsonObject
            val request = case.getValue("request").jsonObject
            val type = WritingType.valueOf(request.getValue("writingType").jsonPrimitive.content)
            val origin = request.getValue("originLanguage").jsonPrimitive.content
            val band = WritingDifficultyPolicy.targetBand(
                request.getValue("languageComplexity").jsonObject.getValue("baseComplexityBand").jsonPrimitive.int,
                "NORMAL",
            )
            assertEquals(case.getValue("schema"), WritingGenerationSchema.build(request, type, band, origin), name)
        }
    }
}
