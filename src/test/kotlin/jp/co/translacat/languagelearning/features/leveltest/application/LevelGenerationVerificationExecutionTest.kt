package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelTestItemType
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelGenerationVerificationPolicy
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionCommand
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionPort
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionResult
import jp.co.translacat.languagelearning.shared.ai.ModelTier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class LevelGenerationVerificationExecutionTest {
    private val cases = Json.parseToJsonElement(
        checkNotNull(
            javaClass.getResource(
                "/contracts/leveltest-verification-python-golden.json",
            ),
        ).readText(),
    ).jsonArray
    private val now = Instant.parse("2026-09-26T04:00:00Z")

    @Test
    fun `Python 독립 선택지 판정과 두 검증 프롬프트를 보존한다`() {
        // 준비
        val execution = LevelGenerationVerificationExecution(ModelExecutionPort { error("순수 정책 테스트입니다.") })
        for (element in cases) {
            val fixture = element.jsonObject
            val candidate = fixture.getValue("candidate").jsonObject
            val verdicts = fixture.getValue("verdicts").jsonArray.map { it.jsonObject }
            val selection = fixture.getValue("selection").jsonPrimitive.content

            // 실행·검증: 품질 거부 사유와 보정 가능성도 원본 결과 그대로 비교한다.
            assertEquals(
                selection,
                LevelGenerationVerificationPolicy.selection(
                    LevelTestItemType.VOCAB_CONTEXT_CHOICE,
                    candidate.getValue("complexityBand").jsonPrimitive.int,
                ),
            )
            assertEquals(
                fixture.getValue("passes").jsonPrimitive.int, LevelGenerationVerificationPolicy.passes(selection),
            )
            assertEquals(
                fixture.getValue("rejection").jsonPrimitive.contentOrNull,
                LevelGenerationVerificationPolicy.rejection(candidate, verdicts.first(), selection),
            )
            assertEquals(
                fixture.getValue("repairable").jsonPrimitive.boolean,
                LevelGenerationVerificationPolicy.repairable(candidate, verdicts.first(), selection),
            )
            assertEquals(
                fixture.getValue("scores").jsonObject.mapValues { it.value.jsonPrimitive.int },
                LevelGenerationVerificationPolicy.optionScores(candidate, selection, verdicts),
            )
            for (pass in 1..2) assertEquals(
                fixture.getValue("prompts").jsonArray[pass - 1].jsonPrimitive.content,
                execution.choicePrompt(candidate, "ko", "en", selection, pass),
            )
        }
    }

    @Test
    fun `BEST ANSWER는 두 번 독립 검증하고 숫자 점수는 LL이 계산한다`() = runBlocking {
        // 준비
        val fixture = cases[2].jsonObject
        val verdicts = fixture.getValue("verdicts").jsonArray
        val calls = mutableListOf<ModelExecutionCommand>()
        val execution = LevelGenerationVerificationExecution(
            ModelExecutionPort { command ->
                calls += command
                ModelExecutionResult(verdicts[calls.size - 1], 5, 4, "synthetic", "synthetic")
            },
            Clock.fixed(now, ZoneOffset.UTC),
        )

        // 실행
        val result =
            execution.choice("synthetic", "ko", "en", fixture.getValue("candidate").jsonObject, now.plusSeconds(180))

        // 검증
        assertNull(result.reason)
        assertEquals(2, calls.size)
        assertEquals(mapOf("A" to 100, "B" to 10, "C" to 10, "D" to 0), result.optionScores)
        assertTrue(
            calls.all {
                it.maxOutputTokens == 2048 && it.tier == ModelTier.MINI && it.deadlineUtc == now.plusSeconds(
                    30,
                )
            },
        )
    }

    @Test
    fun `잘못된 검증 Schema는 후보 거부로 변환하지 않는다`() = runBlocking {
        // 준비
        val execution = LevelGenerationVerificationExecution(
            ModelExecutionPort {
                ModelExecutionResult(buildJsonObject { put("verifiable", false) }, 0, 0, "synthetic", "synthetic")
            },
            Clock.fixed(now, ZoneOffset.UTC),
        )

        // 실행·검증
        val failure = assertFailsWith<LevelTestException> {
            execution.choice(
                "synthetic", "ko", "en", cases.first().jsonObject.getValue("candidate").jsonObject,
                now.plusSeconds(180),
            )
        }
        assertEquals("LEVEL_TEST_VERIFIER_SCHEMA_INVALID", failure.code)
    }
}
