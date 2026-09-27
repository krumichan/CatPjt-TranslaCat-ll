package jp.co.translacat.languagelearning.features.practice

import jp.co.translacat.languagelearning.features.practice.domain.PracticePolicy
import jp.co.translacat.languagelearning.features.practice.domain.ReadingSemantics
import jp.co.translacat.languagelearning.features.practice.domain.ReadingVerdict
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadingPythonGoldenTest {
    private val golden = Json.parseToJsonElement(
        checkNotNull(
            javaClass.getResourceAsStream(
                "/contracts/practice-python-golden.json",
            ),
        ).bufferedReader(Charsets.UTF_8).use { it.readText() },
    ).jsonObject

    @Test
    fun `Python 전체 Reading 모드 band curriculum과 동일하다`() {
        // 준비
        val cases = golden.getValue("slots").jsonArray

        // 실행 및 검증
        cases.forEach { item ->
            val value = item.jsonObject
            val actual = PracticePolicy.slots(
                value.getValue("mode").jsonPrimitive.content, value.getValue("band").jsonPrimitive.int,
            )
            val expected = value.getValue("slots").jsonArray.map { it.jsonObject }
            assertEquals(expected.map { it.getValue("globalOrder").jsonPrimitive.int }, actual.map { it.globalOrder })
            assertEquals(
                expected.map { it.getValue("difficulty").jsonPrimitive.content }, actual.map { it.difficulty.name },
            )
            assertEquals(
                expected.map { it.getValue("complexityBand").jsonPrimitive.int }, actual.map { it.complexityBand },
            )
            assertEquals(expected.map { it.getValue("skillTag").jsonPrimitive.content }, actual.map { it.skillTag })
        }
    }

    @Test
    fun `Python semantic 판정의 복합 실패 우선순위를 유지한다`() {
        // 준비
        val cases = golden.getValue("semantics").jsonArray

        // 실행 및 검증
        cases.forEach { item ->
            val value = item.jsonObject
            val input = value.getValue("assessment").jsonObject
            fun flag(key: String) = input.getValue(key).jsonPrimitive.boolean
            val verdict = ReadingVerdict(
                1, input.getValue("best_answer_key").jsonPrimitive.content,
                flag("ambiguous"), flag("supported"), flag("mode_fit"), flag("answer_leakage"),
                flag("distractors_plausible"),
                flag("stem_presuppositions_supported"), flag("distinct_reading_task"),
                input.getValue("reading_operation").jsonPrimitive.content,
                listOf("p1:s1"),
            )
            val reason = ReadingSemantics.rejection(
                verdict, "A", value.getValue("mode").jsonPrimitive.content,
                value.getValue("skill").jsonPrimitive.content, value.getValue("position").jsonPrimitive.int,
            )
            assertEquals(value.getValue("action").jsonPrimitive.content, if (reason == null) "ACCEPT" else "REJECT")
            assertEquals(
                value.getValue("reason").jsonPrimitive.content, reason ?: "semantic verifier accepted candidate",
            )
        }
    }
}
