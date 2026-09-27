package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.application.WritingEvaluationContextBuilder
import jp.co.translacat.languagelearning.features.writing.domain.model.*
import kotlinx.serialization.json.*
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class WritingEvaluationGoldenTest {
    private val golden = Json.parseToJsonElement(
        requireNotNull(javaClass.getResource("/contracts/writing-evaluation-python-golden.json")).readText(),
    ).jsonObject

    @Test
    fun `Python 원본 합성 점수와 프롬프트를 대조한다`() {
        golden.getValue("scores").jsonArray.forEach { case ->
            val raw = case.jsonObject.getValue("raw").jsonObject
            val result = WritingScoring.score(
                WritingRawScores(
                    raw.getValue("meaning").jsonPrimitive.double,
                    raw.getValue("grammar").jsonPrimitive.double,
                    raw.getValue("vocabulary").jsonPrimitive.double,
                    raw.getValue("naturalness").jsonPrimitive.double,
                    raw.getValue("expression").jsonPrimitive.double,
                ),
            )
            assertEquals(case.jsonObject.getValue("overall").jsonPrimitive.int, result.overall)
            val rounded = case.jsonObject.getValue("rounded").jsonObject
            assertEquals(rounded.getValue("meaning").jsonPrimitive.int, result.meaning)
            assertEquals(rounded.getValue("grammar").jsonPrimitive.int, result.grammar)
            assertEquals(rounded.getValue("vocabulary").jsonPrimitive.int, result.vocabulary)
            assertEquals(rounded.getValue("naturalness").jsonPrimitive.int, result.naturalness)
            assertEquals(rounded.getValue("expression").jsonPrimitive.int, result.expression)
        }
        assertEquals(
            golden.getValue("prompt").jsonPrimitive.content,
            WritingEvaluationAssets.prompt(
                "DAILY", "ko", "en", golden.getValue("compactPayload").jsonPrimitive.content,
            ),
        )
    }

    @Test
    fun `저장된 Writing 상태에서 만든 평가 요청은 Python Pydantic payload와 같다`() {
        val date = LocalDate.parse("2026-09-26")
        val set = WritingSet(
            1, 101, date, WritingType.FREE, "synthetic", 1, WritingSetStatus.READY,
            "{}", "v1", 0, null, null, null,
        )
        val item = WritingItem(2, 1, 101, 1, WritingDifficulty.NORMAL, "합성 질문", "[]", "[]", "합성 포커스", "[]", "[]", "[]")
        val answer = WritingAnswer(3, 2, 101, date, "A synthetic answer.", WritingEvaluationStatus.PENDING)
        val context =
            WritingEvaluationContextBuilder.build("synthetic-writing-eval", set, item, answer, "ko", "en", date)
        assertEquals(golden.getValue("compactPayload").jsonPrimitive.content, context.compactRequestJson)
        val rich = WritingEvaluationContextBuilder.build(
            "synthetic-writing-eval",
            set.copy(snapshotJson = golden.getValue("richSnapshotJson").jsonPrimitive.content),
            item.copy(keywordsJson = "[\"travel\"]"), answer, "ko", "en", date,
        )
        assertEquals(golden.getValue("richCompactPayload").jsonPrimitive.content, rich.compactRequestJson)
        assertEquals(listOf("travel"), rich.canonicalKeys)
    }
}
