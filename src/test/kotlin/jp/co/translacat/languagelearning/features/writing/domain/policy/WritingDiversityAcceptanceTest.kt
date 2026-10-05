package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*
import kotlin.test.*

/** 합성 내용으로 중복 계약을 검사한다. 실모델의 의미적 품질 검증은 아니다. */
class WritingDiversityAcceptanceTest {
    @Test
    fun `이름과 숫자만 바꾼 동일 상황과 목적은 다른 문제로 인정하지 않는다`() {
        // 준비: 본문 문자열이 달라도 동일한 의사소통 과제인 두 후보다.
        val metadata = metadata("WORK", "REQUEST", "DEADLINE_EXTENSION")
        val accepted = listOf("민수에게 보고서 기한을 이틀 늘려 달라고 요청하세요." to metadata)

        // 실행
        val decision = WritingDiversityValidator().validate(
            "수진에게 제안서 마감일을 사흘 늦춰 달라고 부탁하세요.", metadata, accepted,
        )

        // 검증
        assertFalse(decision.accepted)
        assertEquals("STRUCTURAL", decision.reason)
    }

    @Test
    fun `같은 핵심 표현도 다른 상황과 목적의 연습에서는 재사용할 수 있다`() {
        // 준비: please와 같은 정상 표현 재사용은 같은 과제의 반복과 구분한다.
        val accepted = listOf(
            "Please send the document before Friday." to metadata("WORK", "REQUEST", "DOCUMENT_REQUEST"),
        )

        // 실행
        val decision = WritingDiversityValidator().validate(
            "Please explain which bus reaches the museum.",
            metadata("TRAVEL", "ASK_INFORMATION", "ROUTE_QUESTION"), accepted,
        )

        // 검증
        assertTrue(decision.accepted)
    }

    @Test
    fun `최근 같은 과제는 이름 변경으로 우회할 수 없지만 빈 이력은 신규 학습을 허용한다`() {
        // 준비
        val metadata = metadata("SERVICE", "APOLOGIZE", "LATE_ARRIVAL")
        val history = buildJsonObject {
            put("sameFeatureRecent", buildJsonArray {
                add(JsonObject(metadata + mapOf(
                    "content" to JsonPrimitive("예약 시간에 늦은 이유를 식당 직원에게 설명하고 사과하세요."),
                    "ageDays" to JsonPrimitive(2),
                )))
            })
        }
        val candidate = "호텔 안내 직원에게 약속보다 늦게 도착한 이유를 설명하며 사과하세요."

        // 실행
        val repeated = WritingDiversityValidator(history).validate(candidate, metadata)
        val newLearning = WritingDiversityValidator().validate(candidate, metadata)

        // 검증
        assertEquals("STRUCTURAL", repeated.reason)
        assertFalse(repeated.accepted)
        assertTrue(newLearning.accepted)
    }

    private fun metadata(scenario: String, intent: String, archetype: String) = buildJsonObject {
        put("scenarioCategory", scenario)
        put("communicativeIntent", intent)
        put("taskArchetype", archetype)
        put("semanticSummary", "$scenario $intent $archetype")
        put("grammarFocusCodes", buildJsonArray { add("POLITE_REQUEST") })
        put("requiresBackgroundKnowledge", false)
    }
}
