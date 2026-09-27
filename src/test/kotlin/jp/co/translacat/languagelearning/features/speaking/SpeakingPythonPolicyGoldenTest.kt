package jp.co.translacat.languagelearning.features.speaking

import jp.co.translacat.languagelearning.features.speaking.domain.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpeakingPythonPolicyGoldenTest {
    private val source = Json.parseToJsonElement(
        checkNotNull(
            javaClass.getResourceAsStream(
                "/contracts/speaking-policy-python-golden.json",
            ),
        ).bufferedReader().use { it.readText() },
    ).jsonObject

    @Test
    fun `기존 사전검사와 실제 transcript 근거 판정을 각각 유지한다`() {
        // 준비
        for (case in source.getValue("eligibility").jsonArray.map { it.jsonObject }) {
            val turns = Json.decodeFromJsonElement<List<SpeakingEvidenceTurn>>(case.getValue("turns"))

            // 실행
            val result = SpeakingPolicy.eligibility(turns)
            val usable = turns.map { SpeakingPolicy.transcriptUsable(it) }

            // 검증
            assertEquals(Json.decodeFromJsonElement<SpeakingEligibility>(case.getValue("result")), result)
            assertEquals(case.getValue("usable").jsonArray.map { it.jsonPrimitive.boolean }, usable)
        }
    }

    @Test
    fun `평가 가능 축의 기존 가중치와 assistance 우선순위를 보존한다`() {
        // 준비 및 실행: 모든 입력은 Python 원본 함수가 계산한 합성 golden이다.
        for (case in source.getValue("scores").jsonArray.map { it.jsonObject }) {
            val metrics = Json.decodeFromJsonElement<List<SpeakingMetricValue>>(case.getValue("metrics"))
            SpeakingPolicy.requireMetricSet(metrics)
            val result = SpeakingPolicy.overall(metrics)

            // 검증
            assertEquals(case.getValue("result").jsonPrimitive.intOrNull, result)
        }
        for (case in source.getValue("assistance").jsonArray.map { it.jsonObject }) {
            val values =
                case.getValue("types").jsonArray.map { SpeakingAssistanceType.valueOf(it.jsonPrimitive.content) }
            assertEquals(case.getValue("result").jsonPrimitive.content, SpeakingPolicy.assistanceLevel(values).name)
        }
    }

    @Test
    fun `텍스트 평가기의 음향 축 제한과 중복 축 거부를 유지한다`() {
        // 준비 및 실행
        for ((mode, raw) in source.getValue("unsupported").jsonObject) {
            val result = SpeakingPolicy.unsupportedMetrics(SpeakingPracticeMode.valueOf(mode))

            // 검증
            assertEquals(
                raw.jsonObject.mapKeys { SpeakingMetricType.valueOf(it.key) }
                    .mapValues { it.value.jsonPrimitive.content },
                result,
            )
        }
        val duplicate = List(8) { SpeakingMetricValue(SpeakingMetricType.MEANING, SpeakingMetricState.EVALUATED, 80.0) }
        assertFailsWith<IllegalArgumentException> { SpeakingPolicy.requireMetricSet(duplicate) }
    }
}
