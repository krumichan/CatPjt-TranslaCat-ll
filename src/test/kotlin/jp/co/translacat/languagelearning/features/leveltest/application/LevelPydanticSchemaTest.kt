package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelGenerationAssets
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LevelPydanticSchemaTest {
    @Test
    fun `원본 Pydantic이 허용한 숫자와 boolean 형식만 수용한다`() {
        // 준비
        val cases = Json.parseToJsonElement(
            checkNotNull(
                javaClass.getResource(
                    "/contracts/leveltest-schema-coercion-python-golden.json",
                ),
            ).readText(),
        ).jsonArray
        for (element in cases) {
            val fixture = element.jsonObject
            val schema = LevelGenerationAssets.baseSchema(fixture.getValue("asset").jsonPrimitive.content)
            val expected = fixture.getValue("output")

            // 실행·검증: parser 정책은 원본의 허용 형식과 오류 위치를 보존한다.
            if (expected != JsonNull) assertEquals(
                expected, LevelPydanticSchema.decode(fixture.getValue("input"), schema),
            )
            else {
                val failure = assertFailsWith<LevelSchemaFailure> {
                    LevelPydanticSchema.decode(
                        fixture.getValue("input"), schema,
                    )
                }
                assertEquals(
                    fixture.getValue("issues").jsonArray.map { issue ->
                        LevelSchemaIssue(
                            issue.jsonObject.getValue("path").jsonPrimitive.content,
                            issue.jsonObject.getValue("code").jsonPrimitive.content,
                        )
                    },
                    failure.issues, fixture.getValue("name").jsonPrimitive.content,
                )
            }
        }
    }
}
