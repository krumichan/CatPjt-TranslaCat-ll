package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackV3Policy.Segment
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** 동결된 이전 입력을 새 계약으로만 재생할 때 prompt 보존과 실제 서버 validator를 같은 코드로 확인한다. */
class CuratedWritingFeedbackV9ProviderFixtureTest {
    @Test
    fun `v9 provider fixture keeps assignment input and validates any captured live output`() {
        val context = CuratedWritingFeedbackPolicy.Context(WritingType.FREE, buildJsonObject {}, "ko", "ja")
        val requirements = listOf("C1" to "자료를 고르고 이유를 설명할 것")
        val answer = "本を選びました。理由はまだ決めていません。"
        val segments = CuratedWritingFeedbackPolicy.segments(answer)
        assertEquals("curated-writing-feedback-v9", CuratedWritingFeedbackV9Policy.version)
        assertEquals(setOf("C1"), CuratedWritingFeedbackV9Policy.schema(context, requirements, segments)
            .getValue("properties").jsonObject.getValue("observations").jsonObject
            .getValue("properties").jsonObject.keys)
        assertEquals(answer, Json.parseToJsonElement(CuratedWritingFeedbackV9Policy.prompt(context,
            buildJsonObject {}, answer, requirements, segments)).jsonObject
            .getValue("learnerAnswer").jsonObject.getValue("text").jsonPrimitive.content)

        val manifestPath = System.getenv("LL_V9_REPLAY_MANIFEST") ?: return
        val outputDirectory = Path.of(requireNotNull(System.getenv("LL_V9_REPLAY_OUTPUT")))
        require(Files.isDirectory(outputDirectory))
        val manifest = Json.parseToJsonElement(Files.readString(Path.of(manifestPath))).jsonArray
        require(manifest.isNotEmpty() && manifest.size <= 12)
        val invalid = mutableListOf<String>()
        manifest.forEach { entry ->
            val case = entry.jsonObject
            val id = case.getValue("caseId").jsonPrimitive.content
            require(Regex("[a-z0-9-]{1,80}").matches(id))
            val source = Json.parseToJsonElement(Files.readString(
                Path.of(case.getValue("providerRequestPath").jsonPrimitive.content))).jsonObject
            val priorPrompt = Json.parseToJsonElement(source.getValue("input").jsonArray.single().jsonObject
                .getValue("content").jsonPrimitive.content).jsonObject
            val task = priorPrompt.getValue("task").jsonObject
            val learner = priorPrompt.getValue("learnerAnswer").jsonObject
            val caseContext = CuratedWritingFeedbackPolicy.Context(
                WritingType.valueOf(task.getValue("writingType").jsonPrimitive.content),
                task.getValue("publicPromptAndConditions").jsonObject,
                task.getValue("originLanguage").jsonPrimitive.content,
                task.getValue("learningLanguage").jsonPrimitive.content,
            )
            val caseRequirements = task.getValue("requirements").jsonArray.map {
                val requirement = it.jsonObject
                requirement.getValue("id").jsonPrimitive.content to requirement.getValue("text").jsonPrimitive.content
            }
            val caseSegments = learner.getValue("segments").jsonArray.map {
                Segment(it.jsonObject.getValue("id").jsonPrimitive.content,
                    it.jsonObject.getValue("text").jsonPrimitive.content)
            }
            val reference = priorPrompt["referenceExamples"]?.jsonObject?.let {
                buildJsonObject {
                    put("referenceAnswers", it.getValue("answers"))
                    put("alternativeNote", it.getValue("alternativeNote"))
                }
            } ?: buildJsonObject {}
            val regenerated = Json.parseToJsonElement(CuratedWritingFeedbackV9Policy.prompt(caseContext,
                reference, learner.getValue("text").jsonPrimitive.content, caseRequirements, caseSegments))
            assertEquals(priorPrompt, regenerated, "Assignment input changed for $id")
            val schema = CuratedWritingFeedbackV9Policy.schema(caseContext, caseRequirements, caseSegments)
            val command = buildJsonObject {
                put("traceId", "v9-$id")
                put("instructions", CuratedWritingFeedbackV9Policy.instructions)
                put("messages", buildJsonArray { add(buildJsonObject {
                    put("role", "user"); put("content", regenerated.toString())
                }) })
                put("tier", "MINI")
                put("reasoningEffort", "low")
                put("verbosity", "low")
                put("maxOutputTokens", 4096)
                put("remainingMilliseconds", 240000)
                put("maxProviderCalls", 1)
                put("responseSchema", schema)
                put("schemaName", "curated_writing_feedback")
                put("strict", true)
                put("taskName", "LANGUAGE_LEARNING_CURATED_WRITING_FEEDBACK")
            }
            val commandPath = outputDirectory.resolve("$id.command.json")
            if (Files.exists(commandPath)) assertEquals(command, Json.parseToJsonElement(Files.readString(commandPath)))
            else Files.writeString(commandPath, command.toString())
            val responsePath = outputDirectory.resolve("$id.response.json")
            if (Files.exists(responsePath)) {
                val body = Json.parseToJsonElement(Files.readString(responsePath)).jsonObject
                val output = body["output"]
                if (output == null) invalid += "$id:MODEL_RESPONSE_MISSING_OUTPUT" else try {
                    val saved = CuratedWritingFeedbackV9Policy.validate(output, caseContext,
                        caseRequirements, caseSegments)
                    val validatedPath = outputDirectory.resolve("$id.validated.json")
                    if (Files.exists(validatedPath)) assertEquals(saved,
                        Json.parseToJsonElement(Files.readString(validatedPath)))
                    else Files.writeString(validatedPath, saved.toString())
                } catch (failure: IllegalArgumentException) {
                    invalid += "$id:${failure.message}"
                }
            }
        }
        assertTrue(invalid.isEmpty(), "v9 actual provider outputs rejected: ${invalid.joinToString()}")
    }
}
