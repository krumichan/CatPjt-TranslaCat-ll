package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelGenerationDiversity
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionCommand
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionPort
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionResult
import jp.co.translacat.languagelearning.shared.ai.ModelTier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LevelDesignDiversityTest {
    @Test
    fun `설계 구조 fallback와 승인 설계 결합을 Python 기준으로 보존한다`() = runBlocking {
        // 준비
        val fixture = resource("vocab-design").jsonObject
        val request = fixture.getValue("request").jsonObject
        val designs = fixture.getValue("designs").jsonArray.map { it.jsonObject }
        val output = mutableListOf<JsonElement>()
        val calls = mutableListOf<ModelExecutionCommand>()
        val service = LevelVocabDesignExecution(
            ModelExecutionPort { command ->
                calls += command
                ModelExecutionResult(output.removeAt(0), 7, 4, "synthetic", "synthetic")
            },
        )

        // 실행·검증: 실패한 설계는 후보 승인으로 바뀌지 않고 direct generation 단계로 넘어갈 값만 반환한다.
        for (case in fixture.getValue("cases").jsonArray.map { it.jsonObject }) {
            output += case.getValue("raw")
            val result =
                service.plan(
                    request, 2, fixture.getValue("rejected").jsonArray.map { it.jsonPrimitive.content },
                    Instant.now().plusSeconds(90),
                )
            assertEquals(case["output"], result.value?.let(::JsonArray) ?: JsonNull)
            assertEquals(7, result.inputTokens)
            assertEquals(fixture.getValue("planPrompt").jsonPrimitive.content, calls.last().messages.single().content)
        }
        for (case in fixture.getValue("bindingCases").jsonArray.map { it.jsonObject }) {
            assertEquals(
                case.getValue("reason").jsonPrimitive.contentOrNull,
                service.binding(case.getValue("candidate").jsonObject, designs).second,
            )
        }
        assertEquals(
            fixture.getValue("repairPrompt").jsonPrimitive.content,
            service.repairPrompt(
                fixture.getValue("candidate").jsonObject, designs.first(), fixture.getValue("verdict").jsonObject,
                "UNIQUE_ANSWER",
            ),
        )
        assertTrue(calls.all { it.tier == ModelTier.LUNA && it.maxOutputTokens == 4096 })
    }

    @Test
    fun `70개 이력과 Unicode 사례의 다양성 정책이 Python 원본과 일치한다`() {
        // 준비
        val cases = resource("diversity").jsonArray

        // 실행·검증: 완화는 과거 이력 유사도에만 적용하고 같은 세션 구조와 정확 중복은 유지한다.
        for ((index, element) in cases.withIndex()) {
            val fixture = element.jsonObject
            val candidate = fixture.getValue("candidate").jsonObject
            val text = candidate.getValue("promptText").jsonPrimitive.content
            val result = LevelGenerationDiversity(
                fixture.getValue("context").jsonObject,
                fixture.getValue("relaxed").jsonPrimitive.boolean,
            ).validate(candidate)
            assertEquals(fixture.getValue("reason").jsonPrimitive.contentOrNull, result.reason, "case=$index")
            assertEquals(fixture.getValue("metadata"), result.metadata, "case=$index")
            assertEquals(
                fixture.getValue("normalized").jsonPrimitive.content, LevelGenerationDiversity.normalize(text),
                "case=$index",
            )
            assertEquals(
                fixture.getValue("cosine").jsonPrimitive.double,
                LevelGenerationDiversity.cosine(text, fixture.getValue("comparison").jsonPrimitive.content), 1e-14,
                "case=$index",
            )
        }
    }

    private fun resource(name: String) = Json.parseToJsonElement(
        checkNotNull(
            javaClass.getResource(
                "/contracts/leveltest-$name-python-golden.json",
            ),
        ).readText(),
    )
}
