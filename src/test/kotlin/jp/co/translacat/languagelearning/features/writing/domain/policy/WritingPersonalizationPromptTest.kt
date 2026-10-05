package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import kotlin.test.*

class WritingPersonalizationPromptTest {
    @Test
    fun `언어 귀속 불명 누적 약점과 성취는 생성 요청에서 제외하고 기존 band와 현재 근거는 보존한다`() {
        // 준비: 실제 요청 fixture에 다른 언어에서 왔을 수 있는 누적 signal을 넣는다.
        val base = Json.parseToJsonElement(checkNotNull(javaClass.getResource(
            "/contracts/writing-candidate-policy-python-golden.json",
        )).readText()).jsonArray.first().jsonObject.getValue("request").jsonObject
        val currentEvidence = buildJsonObject {
            put("sampleCount", 1); put("overallAverage", 72)
            put("learningLanguage", base.getValue("learningLanguage"))
            put("writingType", base.getValue("writingType"))
            put("scoringPolicyVersion", WritingScoring.policyVersion)
            put("evaluationRubricVersion", WritingScoring.rubricVersion)
        }
        val profile = buildJsonObject {
            put("profileVersion", "PROFILE")
            put("baseLevelScore", 60)
            put("languageScope", "UNKNOWN")
            put("learningLanguage", JsonNull)
            put("skillScores", buildJsonObject { put("grammar", 99) })
            put("grammarWeaknesses", buildJsonArray { add("Unattributed weakness") })
            put("recommendedFocus", buildJsonArray { add("Unattributed suggestion") })
            put("keywordMasteries", buildJsonArray { add("Unattributed mastery") })
        }
        val request = JsonObject(base + mapOf(
            "learningProfile" to profile,
            "recentEvaluationSummary" to currentEvidence,
            "recentMistakes" to buildJsonArray { add("Unattributed weakness") },
            "recentlyLearnedExpressions" to buildJsonArray { add("Unattributed suggestion") },
        ))

        // 실행: 실제 생성기가 전송하는 프롬프트를 조립한다.
        val prompt = WritingGenerationPrompt.build(
            request, WritingType.valueOf(base.getValue("writingType").jsonPrimitive.content), 3,
        )
        val payload = Json.parseToJsonElement(
            prompt.substringAfter("<learning-data>\n").substringBefore("\n</learning-data>"),
        ).jsonObject

        // 검증: 불명 약점은 새 평가로 환산하지 않으며 확인된 최근 점수와 기존 목표 band를 유지한다.
        val visibleProfile = payload.getValue("learningProfile").jsonObject
        assertEquals(setOf("profileVersion", "baseLevelScore", "languageScope", "learningLanguage"), visibleProfile.keys)
        assertEquals(JsonPrimitive(60), visibleProfile["baseLevelScore"])
        assertEquals(currentEvidence, payload["recentEvaluationSummary"])
        assertEquals(JsonArray(emptyList()), payload["recentMistakes"])
        assertEquals(JsonArray(emptyList()), payload["recentlyLearnedExpressions"])
        assertEquals(3, payload.getValue("generationPlan").jsonObject.getValue("targetBand").jsonPrimitive.int)
        assertFalse(prompt.contains("Unattributed"))
        assertEquals(profile, request["learningProfile"])

        // 검증: 저장된 구형 snapshot의 언어 미상 평균도 재생성에서 현재 성취로 되살리지 않는다.
        val legacyRequest = JsonObject(request + mapOf(
            "learningProfile" to JsonObject(profile - "languageScope" - "learningLanguage"),
            "recentEvaluationSummary" to buildJsonObject { put("overallAverage", 99) },
        ))
        val legacyPrompt = WritingGenerationPrompt.build(
            legacyRequest, WritingType.valueOf(base.getValue("writingType").jsonPrimitive.content), 3,
        )
        val legacyPayload = Json.parseToJsonElement(
            legacyPrompt.substringAfter("<learning-data>\n").substringBefore("\n</learning-data>"),
        ).jsonObject
        assertFalse("recentEvaluationSummary" in legacyPayload)
        assertFalse(legacyPrompt.contains("Unattributed"))
    }
}
