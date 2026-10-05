package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.application.WritingGenerationExecution
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionCommand
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionPort
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WritingGuidedLanguageContractTest {
    @Test
    fun `Guided 생성 경계는 모든 안내 배열의 원어와 학습자 답변 언어를 구분한다`() = runBlocking {
        // 준비: 실제 Provider 대신 실행 경계에서 요청을 포착한다.
        val baseline = Json.parseToJsonElement(
            requireNotNull(javaClass.getResource("/contracts/writing-generation-python-golden.json")).readText(),
        ).jsonObject.getValue("request").jsonObject

        for ((origin, learning) in listOf("ko" to "en", "ja" to "en", "en" to "ko")) {
            val request = JsonObject(baseline + mapOf(
                "writingType" to JsonPrimitive("GUIDED"),
                "originLanguage" to JsonPrimitive(origin),
                "learningLanguage" to JsonPrimitive(learning),
            ))
            var command: ModelExecutionCommand? = null
            val model = ModelExecutionPort {
                command = it
                ModelExecutionResult(buildJsonObject { put("items", JsonArray(emptyList())) }, 0, 0, "test", "test")
            }

            // 실행: 실제 생성 서비스가 읽는 system prompt와 요청별 Schema를 함께 검사한다.
            WritingGenerationExecution(model).generate(
                request, WritingType.GUIDED, 3, 1, Instant.now().plusSeconds(10),
            )

            // 검증: 세 안내 필드는 원어로 전달하고 답안은 생성하지 않으며 기존 크기 제한을 유지한다.
            val submitted = checkNotNull(command)
            assertTrue(submitted.strict)
            val properties = checkNotNull(submitted.responseSchema).getValue("\$defs").jsonObject
                .getValue("WritingDraft").jsonObject.getValue("properties").jsonObject
            val spec = WritingDifficultyPolicy.spec(origin, WritingType.GUIDED, 3)
            for (name in listOf("providedFacts", "requiredIntents", "responseConstraints")) {
                assertTrue(submitted.instructions.contains("$name MUST be written in originLanguage"), name)
                val field = properties.getValue(name).jsonObject
                val description = field.getValue("description").jsonPrimitive.content
                assertTrue(description.contains("originLanguage ($origin)"), name)
                assertTrue(description.contains("learningLanguage ($learning)"), name)
                assertEquals(spec.guidanceMinEntries, field.getValue("minItems").jsonPrimitive.int)
                assertEquals(spec.guidanceMaxEntries, field.getValue("maxItems").jsonPrimitive.int)
            }
        }
    }
}
