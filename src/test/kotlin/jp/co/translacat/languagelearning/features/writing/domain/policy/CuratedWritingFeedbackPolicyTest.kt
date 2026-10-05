package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*
import kotlin.test.*

class CuratedWritingFeedbackPolicyTest {
    private val answer = "欠席した生徒も増えていました。"
    private val requirements = listOf("C1" to "결석한 학생이 증가했음을 전달한다")
    private val segments = CuratedWritingFeedbackPolicy.segments(answer)

    private fun modelResult(id: String = "S1", extra: Boolean = false) = buildJsonObject {
        put("observations", buildJsonArray { add(buildJsonObject {
            put("requirementId", "C1"); put("status", "MET")
            put("segmentIds", buildJsonArray { add(id) })
            put("originText", "결석한 학생이 늘어난 사실을 전달했습니다.")
            put("learningText", "欠席した生徒が増えたことを伝えています。")
            if (extra) put("answerQuotes", buildJsonArray { add("増っていました") })
        }) })
        put("necessaryCorrections", buildJsonArray {})
        put("optionalAlternatives", buildJsonArray {})
        put("uncertainties", buildJsonArray {})
    }

    @Test
    fun `없는 오타 인용은 거절하고 서버가 실제 답안 원문만 복원한다`() {
        // 준비·실행: 모델은 구간 ID만 선택한다.
        val validated = CuratedWritingFeedbackPolicy.validate(modelResult(), requirements, segments)

        // 검증: 과거 없는 오타 반례를 모델 필드에 넣으면 거절하고 정확한 원문만 남긴다.
        val quote = validated.getValue("observations").jsonArray.single().jsonObject
            .getValue("answerQuotes").jsonArray.single().jsonPrimitive.content
        assertEquals(answer, quote)
        assertFalse(quote.contains("増っていました"))
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackPolicy.validate(modelResult(extra = true), requirements, segments)
        }
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackPolicy.validate(modelResult(id = "S999"), requirements, segments)
        }
    }

    @Test
    fun `요구 ID의 중복 누락과 언어 lane 침범을 거절한다`() {
        // 준비: 필수 의미 두 개에 대해 한 개만 판정한 응답.
        val missing = modelResult()
        val two = requirements + ("C2" to "다른 사실")

        // 실행·검증: 숫자 ID 일치만으로 의미 충족을 주장하지 않고 전부 판정하게 한다.
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackPolicy.validate(missing, two, segments)
        }
        val wrongLanguage = JsonObject(missing + ("observations" to buildJsonArray { add(buildJsonObject {
            put("requirementId", "C1"); put("status", "MET")
            put("segmentIds", buildJsonArray { add("S1") })
            put("originText", "日本語だけ")
            put("learningText", "한국어가 섞였습니다")
        }) }))
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackPolicy.validate(wrongLanguage, requirements, segments)
        }
    }

    @Test
    fun `긴 문단과 빈 줄을 버리지 않고 최대 여든 구간으로 묶는다`() {
        // 준비·실행: 답안의 문장 수는 하루 목표 문항 수와 별개다.
        val paragraph = (1..100).joinToString("\n\n") { "文${it}です。" }
        val parts = CuratedWritingFeedbackPolicy.segments(paragraph)

        // 검증: 공백 줄까지 포함한 원문이 정확히 재구성된다.
        assertTrue(parts.size <= 80)
        assertEquals(paragraph, parts.joinToString("") { it.text })
    }
}
