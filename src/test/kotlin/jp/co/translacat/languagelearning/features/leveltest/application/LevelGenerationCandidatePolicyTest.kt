package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelGenerationCandidatePolicy
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class LevelGenerationCandidatePolicyTest {
    @Test
    fun `Python 후보 내용 검사와 첫 거절 이유를 모든 문항 유형에서 보존한다`() {
        // 준비: 원본의 모든 문항 유형 정상 사례와 표시·정답·언어·참조 오류를 실행한 45개 golden이다.
        val cases = Json.parseToJsonElement(
            checkNotNull(
                javaClass.getResource(
                    "/contracts/leveltest-candidate-policy-python-golden.json",
                ),
            ).readText(),
        ).jsonArray
        for (element in cases) {
            val fixture = element.jsonObject

            // 실행
            val reason = LevelGenerationCandidatePolicy.rejection(
                fixture.getValue("request").jsonObject,
                fixture.getValue("candidate").jsonObject,
            )

            // 검증
            assertEquals(
                fixture.getValue("reason").jsonPrimitive.contentOrNull, reason,
                fixture.getValue("name").jsonPrimitive.content,
            )
        }
    }
}
