package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class WritingDifficultySpecGoldenTest {
    private val golden = Json.parseToJsonElement(
        requireNotNull(javaClass.getResource("/contracts/writing-difficulty-spec-python-golden.json")).readText(),
    ).jsonObject

    @Test
    fun `Python 원본의 유형 밴드 언어별 표면 규칙과 거부 순서를 대조한다`() {
        golden.getValue("bands").jsonArray.forEach { row ->
            val case = row.jsonObject
            assertEquals(
                case.getValue("targetBand").jsonPrimitive.int,
                WritingDifficultyPolicy.targetBand(
                    case.getValue("baseBand").jsonPrimitive.int, case.getValue("difficulty").jsonPrimitive.content,
                ),
            )
        }
        golden.getValue("specs").jsonArray.forEach { row ->
            val case = row.jsonObject
            val expected = case.getValue("spec").jsonObject
            val spec = WritingDifficultyPolicy.spec(
                case.getValue("originLanguage").jsonPrimitive.content,
                WritingType.valueOf(case.getValue("writingType").jsonPrimitive.content),
                case.getValue("targetBand").jsonPrimitive.int,
            )
            assertEquals(expected.getValue("version").jsonPrimitive.content, WritingDifficultyPolicy.version)
            assertEquals(expected.getValue("origin_max_characters").jsonPrimitive.int, spec.originMaxCharacters)
            assertEquals(expected.getValue("origin_max_surface_units").jsonPrimitive.int, spec.originMaxSurfaceUnits)
            assertEquals(expected.getValue("guidance_min_entries").jsonPrimitive.int, spec.guidanceMinEntries)
            assertEquals(expected.getValue("guidance_max_entries").jsonPrimitive.int, spec.guidanceMaxEntries)
            assertEquals(
                expected.getValue("guidance_max_characters_per_entry").jsonPrimitive.int,
                spec.guidanceMaxCharactersPerEntry,
            )
            assertEquals(
                expected.getValue("guidance_max_total_characters").jsonPrimitive.int, spec.guidanceMaxTotalCharacters,
            )
            assertEquals(expected.getValue("note_max_characters").jsonPrimitive.int, spec.noteMaxCharacters)
            assertEquals(expected.getValue("semantic_recipe").jsonPrimitive.content, spec.semanticRecipe)
        }
        val spec = WritingDifficultyPolicy.spec("ko", WritingType.FREE, 3)
        golden.getValue("reasons").jsonArray.forEach { row ->
            val case = row.jsonObject
            fun values(key: String) = case.getValue(key).jsonArray.map { it.jsonPrimitive.content }
            val actual = WritingDifficultyPolicy.reason(
                case.getValue("originText").jsonPrimitive.content,
                case.getValue("focusReason").jsonPrimitive.content, values("providedFacts"), values("requiredIntents"),
                values("responseConstraints"), spec,
            )
            assertEquals(case.getValue("reason").let { if (it is JsonNull) null else it.jsonPrimitive.content }, actual)
        }
    }
}
