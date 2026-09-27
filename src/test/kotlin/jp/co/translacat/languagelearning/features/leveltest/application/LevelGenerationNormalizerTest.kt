package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelGenerationNormalizer
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class LevelGenerationNormalizerTest {
    @Test
    fun `Python 생성 정규화의 모든 item type와 구조 복구 계수를 보존한다`() {
        // 준비: 현재 Python normalizer를 실제 실행한 72개 합성 입력·출력이다.
        val cases = Json.parseToJsonElement(
            checkNotNull(
                javaClass.getResource(
                    "/contracts/leveltest-normalizer-python-golden.json",
                ),
            ).readText(),
        ).jsonArray
        for (element in cases) {
            val fixture = element.jsonObject
            val input = fixture.getValue("input").jsonObject

            // 실행
            val result = LevelGenerationNormalizer.normalize(
                input,
                fixture.getValue("originLanguage").jsonPrimitive.content,
                fixture.getValue("learningLanguage").jsonPrimitive.content,
            )

            // 검증: 정규화는 의미를 만들지 않으며 원본에서 허용한 변환과 진단 계수가 같다.
            val name = fixture.getValue("name").jsonPrimitive.content
            assertEquals(fixture.getValue("output"), result.output, name)
            assertEquals(
                fixture.getValue("stats").jsonObject.mapValues { it.value.jsonPrimitive.int }, result.stats, name,
            )
            assertEquals(fixture.getValue("input"), input, "입력 객체를 수정하지 않는다: $name")
        }
    }
}
