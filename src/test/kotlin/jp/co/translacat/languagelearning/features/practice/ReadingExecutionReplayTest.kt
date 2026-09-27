package jp.co.translacat.languagelearning.features.practice

import jp.co.translacat.languagelearning.features.practice.application.PracticeGenerationClaim
import jp.co.translacat.languagelearning.features.practice.application.ReadingGenerationExecution
import jp.co.translacat.languagelearning.features.practice.domain.PracticeDomain
import jp.co.translacat.languagelearning.features.practice.domain.PracticeQuestionContent
import jp.co.translacat.languagelearning.features.practice.domain.PracticeSet
import jp.co.translacat.languagelearning.features.practice.domain.ReadingRequest
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionPort
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadingExecutionReplayTest {
    @Test
    fun `원본 Python의 실제 업무 경로와 prompt schema 결과 호출 수를 대조한다`() = runBlocking {
        // 준비: 원본 Python 파이프라인이 실제 실행한 입력·출력만 Provider 경계에서 재생한다.
        val cases = Json.parseToJsonElement(
            checkNotNull(
                javaClass.getResourceAsStream(
                    "/contracts/practice-execution-python-golden.json",
                ),
            ).bufferedReader(Charsets.UTF_8).use { it.readText() },
        ).jsonArray
        for (case in cases) {
            val value = case.jsonObject
            val source = value.getValue("request").jsonObject
            val request = ReadingRequest(
                source.getValue("requestId").jsonPrimitive.content, source.getValue("mode").jsonPrimitive.content,
                "ko", "en", source.getValue("complexityBand").jsonPrimitive.int, emptyList(), emptyList(), emptyList(),
                "2026-09-26",
            )
            val calls = value.getValue("calls").jsonArray.map { it.jsonObject }
            val previous =
                Json.decodeFromJsonElement<List<PracticeQuestionContent>>(source.getValue("previousQuestions"))
            val firstOrder = previous.size + 1
            var index = 0
            val provider = ModelExecutionPort { command ->
                val expected = calls[index++]
                assertEquals(expected.getValue("tier").jsonPrimitive.content, command.tier.name)
                assertEquals(expected.getValue("maxOutputTokens").jsonPrimitive.int, command.maxOutputTokens)
                assertEquals<JsonElement?>(expected.getValue("schema"), command.responseSchema)
                val expectedPrompt = expected.getValue("prompt").jsonPrimitive.content
                val actualPrompt = command.messages.single().content
                assertEquals(prefix(expectedPrompt), prefix(actualPrompt))
                assertEquals(payload(expectedPrompt), payload(actualPrompt))
                ModelExecutionResult(expected.getValue("output"), 0, 0, "synthetic", "synthetic")
            }
            val set = PracticeSet(
                1, 101, LocalDate.parse("2026-09-26"), PracticeDomain.READING,
                request.mode, request.complexityBand, 5, Json.encodeToString(request),
                startedAt = LocalDateTime.of(2026, 9, 26, 0, 0),
            )

            // 실행
            val result = ReadingGenerationExecution(provider).generate(
                PracticeGenerationClaim(set, "synthetic", firstOrder, previous),
            )

            // 검증
            assertEquals(calls.size, index)
            assertEquals(
                value.getValue("response").jsonObject.getValue("promptVersion").jsonPrimitive.content,
                result.promptVersion,
            )
            val expected = Json.decodeFromJsonElement<List<PracticeQuestionContent>>(
                value.getValue("response").jsonObject.getValue("questions"),
            )
                .map { it.copy(order = it.order + firstOrder - 1) }
            assertEquals(expected, result.questions)
        }
    }

    private fun prefix(value: String) = if ("<practice-data>\n" in value) value.substringBefore("<practice-data>\n")
    else value.substringBefore("\n\n")

    private fun payload(value: String): JsonElement = Json.parseToJsonElement(
        if ("<practice-data>\n" in value) value.substringAfter("<practice-data>\n")
            .substringBefore("\n</practice-data>")
        else value.substringAfter("\n\n"),
    )
}
