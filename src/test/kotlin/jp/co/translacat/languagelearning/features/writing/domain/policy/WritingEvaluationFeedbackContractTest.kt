package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.application.WritingEvaluationExecution
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionCommand
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionPort
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionResult
import jp.co.translacat.languagelearning.shared.ai.ModelTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WritingEvaluationFeedbackContractTest {
    @Test
    fun `원본 schema는 승인된 bilingual과 pattern 및 fragment 설명 이외에는 그대로 보존한다`() {
        // 준비
        val original = Json.parseToJsonElement(resource("evaluation-schema.json"))
        val bilingualPaths = listOf(
            "properties.strengths.items.properties", "properties.weaknesses.items.properties",
            "properties.explanation.properties", "properties.corrections.items.properties.explanation.properties",
        ).flatMap { parent -> listOf("$parent.originText.description", "$parent.learningText.description") }.toSet()
        val fragmentDescriptions = mapOf(
            "original" to "A non-empty fragment quoted from the learner's actual submitted answer. " +
                "Return the fragment itself as a string, not a translation, explanation, or serialized bilingual feedback object.",
            "corrected" to "The non-empty replacement answer fragment in the request learningLanguage, corresponding to original. " +
                "Return the fragment itself as a string. Do not serialize an object containing originText, learningText, " +
                "category, explanation, or other feedback fields into this string. Bilingual correction reasons belong in the sibling explanation object.",
        )
        val fragmentPaths = fragmentDescriptions.keys.map { "properties.corrections.items.properties.$it.description" }.toSet()
        val profileMetrics = mapOf(
            "grammarPatterns" to "GRAMMAR", "vocabularyPatterns" to "VOCABULARY",
            "naturalnessPatterns" to "NATURALNESS", "expressionPatterns" to "EXPRESSION",
            "meaningPatterns" to "MEANING",
        )
        val profilePaths = profileMetrics.keys.flatMap { field ->
            listOf("properties.profileSignals.properties.$field.description", "properties.profileSignals.properties.$field.items.description")
        }.toSet()

        // 실행
        val paths = descriptionPaths(WritingEvaluationAssets.schema)

        // 검증: 검증기·점수·배열·필수 key 제약을 추가하거나 제거하지 않는다.
        assertEquals(bilingualPaths + profilePaths + fragmentPaths, paths)
        assertEquals(original, withoutDescriptions(WritingEvaluationAssets.schema))
        assertEquals(original, Json.parseToJsonElement(resource("evaluation-schema.json")))
        bilingualPaths.forEach { path ->
            val language = if (path.contains(".originText.")) "originLanguage" else "learningLanguage"
            assertTrue(at(WritingEvaluationAssets.schema, path).jsonPrimitive.content.contains("request $language"))
        }
        fragmentDescriptions.forEach { (field, description) ->
            val node = at(WritingEvaluationAssets.schema, "properties.corrections.items.properties.$field").jsonObject
            assertEquals("string", node.getValue("type").jsonPrimitive.content)
            assertEquals(description, node.getValue("description").jsonPrimitive.content)
        }
        profileMetrics.forEach { (field, metric) ->
            val node = at(WritingEvaluationAssets.schema, "properties.profileSignals.properties.$field").jsonObject
            assertEquals("array", node.getValue("type").jsonPrimitive.content)
            assertEquals(30, node.getValue("maxItems").jsonPrimitive.int)
            assertEquals("string", node.getValue("items").jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(
                "Observed $metric deficiencies only. This array feeds an existing weakness/error-pattern field. " +
                    "Return [] when no actual $metric problem is evidenced in the learner's answer. " +
                    "Put positive observations in strengthTags, not in this array.",
                node.getValue("description").jsonPrimitive.content,
            )
            assertEquals(
                "One specific $metric error or unmet task requirement evidenced in the actual submitted answer. " +
                    "Do not describe correct or neutral usage, optional refinements, or suggested expressions as a deficiency; " +
                    "positive observations belong in strengthTags. If none exists, leave the parent array empty.",
                node.getValue("items").jsonObject.getValue("description").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `원본 평가 지시 뒤에 승인된 피드백 필드 역할만 명시한다`() {
        // 준비: 원본 파일을 고치지 않고 추가 계약을 독립적인 expected로 선언한다.
        val original = resource("evaluation-system-prompt.txt").replace("\r\n", "\n").trimEnd('\r', '\n')
        val addition = """
            # Feedback field language
            - Every originText feedback field, including nested correction explanations, must use the request originLanguage for its explanatory prose.
            - Every learningText feedback field, including nested correction explanations, must use the request learningLanguage for its explanatory prose. Brief exact task/answer quotations may retain their original language; the surrounding explanation must not switch languages or copy the originLanguage explanation.

            # Evidence-based corrections and optional refinements
            - Weaknesses and corrections require an evidenced error or an unmet explicit task constraint. A different acceptable phrasing alone is not an error.
            - Optional style, register, or politeness refinements belong in recommendedAnswers, not weaknesses or corrections, unless the task explicitly requires the unmet register or formality.
            - A correction must preserve already-valid facts, actions, and communicative intent. If an actual task-meaning mismatch needs correction, identify that mismatch explicitly instead of silently replacing an already-correct meaning. Respect the task's stated scope and concision constraints.

            # Profile signal field roles
            - Put demonstrated positive observations from the learner's actual answer in strengthTags. grammarPatterns, vocabularyPatterns, naturalnessPatterns, expressionPatterns, and meaningPatterns feed existing weakness/error-pattern fields: include only evidenced problems in the corresponding metric, never positive observations. Return an empty array when no such problem was observed.
            - recommendedFocus contains practice suggestions, not evidence of expressions already used or mastered. Suggested words, corrected fragments, and recommendedAnswers are not the learner's usage or achievement evidence unless independently present in the actual submitted answer; do not promote a suggested improvement into an observed strength or weakness.
        """.trimIndent()

        // 실행 / 검증
        assertEquals(original + "\n\n" + addition, WritingEvaluationAssets.instructions)
        assertEquals("writing-evaluation", WritingEvaluationAssets.promptVersion)
    }

    @Test
    fun `서로 다른 언어의 동시 요청은 공유 schema와 중첩 설명을 오염시키지 않는다`() = runBlocking {
        // 준비
        val before = WritingEvaluationAssets.schema.toString()
        val pairs = listOf("ko" to "ja", "ja" to "en", "en" to "ko")

        // 실행: 실제 요청 본문만 현재 pair를 포함하고 schema 설명은 요청 필드에 결합한다.
        val prompts = (0 until 60).map { index ->
            async(Dispatchers.Default) {
                val (origin, learning) = pairs[index % pairs.size]
                val prompt = WritingEvaluationAssets.prompt("DAILY", origin, learning, "{}")
                assertEquals(before, WritingEvaluationAssets.schema.toString())
                Triple(origin, learning, prompt)
            }
        }.awaitAll()

        // 검증
        prompts.forEach { (origin, learning, prompt) ->
            assertTrue(prompt.contains("Feedback originText fields MUST be written in $origin."))
            assertTrue(prompt.contains("Feedback learningText fields and recommendedAnswers MUST be written in $learning."))
        }
        assertEquals(before, WritingEvaluationAssets.schema.toString())
    }

    @Test
    fun `기존 공통 실행은 DAILY와 LEVEL_TEST 모두 보완된 계약을 MINI에 전달한다`() = runBlocking {
        // 준비: 실행 파일을 수정하지 않고 실제 모델 포트로 나가는 계약을 검사한다.
        val commands = mutableListOf<ModelExecutionCommand>()
        val model = ModelExecutionPort {
            commands += it
            ModelExecutionResult(payload(), 1, 1, "test", "test")
        }

        // 실행
        listOf("DAILY", "LEVEL_TEST").forEach { context ->
            WritingEvaluationExecution(model).evaluate(
                "synthetic-$context", context, "ko", "ja", "{}", Instant.now().plusSeconds(10),
            )
        }

        // 검증: 기존 모델·strict·토큰 상한과 일반 실행 경로가 유지된다.
        assertEquals(2, commands.size)
        commands.forEach { command ->
            assertEquals(WritingEvaluationAssets.schema, command.responseSchema)
            assertEquals(WritingEvaluationAssets.instructions, command.instructions)
            assertEquals(ModelTier.MINI, command.tier)
            assertTrue(command.strict)
            assertEquals(8192, command.maxOutputTokens)
        }
    }

    private fun resource(name: String) = requireNotNull(javaClass.getResource("/writing/$name")).readText()

    private fun at(root: JsonElement, path: String): JsonElement =
        path.split('.').fold(root) { value, key -> value.jsonObject.getValue(key) }

    private fun descriptionPaths(value: JsonElement, path: String = ""): Set<String> = when (value) {
        is JsonObject -> value.flatMap { (key, child) ->
            val childPath = if (path.isEmpty()) key else "$path.$key"
            if (key == "description") listOf(childPath) else descriptionPaths(child, childPath)
        }.toSet()
        is JsonArray -> value.flatMap { descriptionPaths(it, path) }.toSet()
        else -> emptySet()
    }

    private fun withoutDescriptions(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.filterKeys { it != "description" }.mapValues { withoutDescriptions(it.value) })
        is JsonArray -> JsonArray(value.map(::withoutDescriptions))
        else -> value
    }

    private fun payload() = buildJsonObject {
        put("scores", buildJsonObject {
            listOf("meaning", "grammar", "vocabulary", "naturalness", "expression").forEach { put(it, 80) }
        })
        put("strengths", JsonArray(emptyList()))
        put("weaknesses", JsonArray(emptyList()))
        put("corrections", JsonArray(emptyList()))
        put("recommendedAnswers", buildJsonArray { add("合成例一"); add("合成例二") })
        put("explanation", buildJsonObject { put("originText", "합성 설명"); put("learningText", "合成説明") })
        put("profileSignals", buildJsonObject {})
    }
}
