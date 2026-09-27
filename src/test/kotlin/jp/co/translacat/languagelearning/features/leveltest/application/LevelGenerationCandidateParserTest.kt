package jp.co.translacat.languagelearning.features.leveltest.application

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class LevelGenerationCandidateParserTest {
    @Test
    fun `Python Pydantic 후보별 결과와 오류 위치 및 부분 salvage를 보존한다`() {
        // 준비: 원본 서비스가 출력한 합성 후보·진단만 사용한다.
        val cases = Json.parseToJsonElement(
            checkNotNull(
                javaClass.getResource(
                    "/contracts/leveltest-candidate-parser-python-golden.json",
                ),
            ).readText(),
        ).jsonArray
        for (element in cases) {
            val fixture = element.jsonObject
            val name = fixture.getValue("name").jsonPrimitive.content

            // 실행
            val result = LevelGenerationCandidateParser.batch(fixture.getValue("input").jsonObject)

            // 검증: 정상 형제 후보를 유지하고 재시도 prompt에 들어가는 안전한 진단도 원본과 같다.
            assertEquals(fixture.getValue("rejected").jsonPrimitive.int, result.rejected, name)
            assertEquals(fixture.getValue("reasons").jsonArray.map { it.jsonPrimitive.content }, result.reasons, name)
            assertEquals(
                fixture.getValue("parsed").jsonArray.map { value ->
                    value.jsonObject.getValue("index").jsonPrimitive.int to value.jsonObject.getValue(
                        "candidate",
                    ).jsonObject
                },
                result.candidates, name,
            )
        }
    }
}
