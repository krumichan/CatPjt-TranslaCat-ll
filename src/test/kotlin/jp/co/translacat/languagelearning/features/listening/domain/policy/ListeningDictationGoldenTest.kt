package jp.co.translacat.languagelearning.features.listening.domain.policy

import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningEvaluationContext
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

internal class ListeningDictationGoldenTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `Python 정규화 alignment 점수와 profile 신호를 보존한다`() {
        // 준비: Kotlin 기대값을 만들지 않고 현 Python 실행 결과를 읽는다.
        val cases = Json.parseToJsonElement(
            checkNotNull(
                javaClass.getResourceAsStream(
                    "/contracts/listening-dictation-python-golden.json",
                ),
            ).bufferedReader().use { it.readText() },
        ).jsonArray
        for (entry in cases) {
            val case = entry.jsonObject
            val locale = case.getValue("language").jsonPrimitive.content
            val source = case.getValue("source").jsonPrimitive.content
            val variants = case.getValue(
                "acceptedVariants",
            ).jsonObject.mapValues { it.value.jsonArray.map { value -> value.jsonPrimitive.content } }

            // 실행
            val result =
                ListeningDictation.evaluate(source, case.getValue("answer").jsonPrimitive.content, locale, variants)
            val actual = json.encodeToJsonElement(result).jsonObject
            val expected = case.getValue("task").jsonObject

            // 검증: 기록용 debug metadata를 제외한 평가 계약의 실제 필드를 대조한다.
            assertEquals(
                case.getValue("sourceNormalized").jsonPrimitive.content, ListeningText.normalize(source, locale).text,
            )
            for (field in actual.keys) assertEquals(expected[field], actual[field], "$locale / $field")
        }
    }

    @Test
    fun `연습과 정답 공개는 공식 profile evidence를 만들지 않는다`() {
        // 준비
        val practice = ListeningEvaluationContext(official = false)
        val revealed = ListeningEvaluationContext(answerRevealed = true)

        // 실행
        val practiceResult = ListeningDictation.evaluate("A quiet morning", "A quiet morning", "en", context = practice)
        val revealedResult = ListeningDictation.evaluate("A quiet morning", "A quiet morning", "en", context = revealed)

        // 검증
        assertEquals(100, practiceResult.score)
        assertFalse(practiceResult.profileEligible)
        assertEquals(emptyList(), practiceResult.profileSignals)
        assertEquals("ANSWER_REVEALED", revealedResult.reasonCode)
        assertEquals(null, revealedResult.score)
        assertFalse(revealedResult.profileEligible)
    }
}
