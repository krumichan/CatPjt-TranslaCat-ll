package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelQuestionData
import jp.co.translacat.languagelearning.shared.ai.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class LevelGenerationExecutionTest {
    private val now = Instant.parse("2026-09-26T04:00:00Z")
    private val json = Json { encodeDefaults = true }
    private val fixtures = Json.parseToJsonElement(
        checkNotNull(
            javaClass.getResource(
                "/contracts/leveltest-generation-execution-python-golden.json",
            ),
        ).readText(),
    ).jsonArray.map { it.jsonObject }

    @Test
    fun `전체 20슬롯과 설계 보정 거절 28경로를 원본 Python 업무 루프와 비교한다`() = runBlocking {
        for (fixture in fixtures) {
            // 준비: 업무 실행은 실제 Kotlin이며 원본 Python에서 기록한 모델 출력만 순서대로 공급한다.
            val name = fixture.getValue("name").jsonPrimitive.content
            val expected = fixture.getValue("calls").jsonArray.map { it.jsonObject }
            val commands = mutableListOf<ModelExecutionCommand>()
            val execution = LevelGenerationExecution(
                ModelExecutionPort { command ->
                    val call = expected.getOrNull(commands.size) ?: error("예상보다 많은 호출: $name")
                    commands += command
                    assertEquals(call.getValue("type").jsonPrimitive.content, command.taskName, name)
                    assertEquals(call.getValue("prompt").jsonPrimitive.content, command.messages.single().content, name)
                    ModelExecutionResult(call.getValue("output"), 7, 4, "test-provider", "synthetic-level-generation")
                },
                SpeechExecutionPort { error("음성을 예약하지 않은 합성 계약입니다.") }, { _, _, _ -> error("업로드가 없어야 합니다.") },
                Clock.fixed(now, ZoneOffset.UTC),
            )

            // 실행·검증: 정상·합법적인 거부·잘못된 검증 protocol을 별도로 비교한다.
            if (fixture.getValue("response") != JsonNull) {
                val result = execution.generate(fixture.getValue("request").jsonObject, null, now.plusSeconds(180))
                assertEquals(fixture.getValue("response"), json.encodeToJsonElement<LevelQuestionData>(result), name)
            } else {
                val failure = assertFailsWith<LevelTestException> {
                    execution.generate(fixture.getValue("request").jsonObject, null, now.plusSeconds(180))
                }
                val expectedFailure = fixture.getValue("failure").jsonObject
                assertEquals(expectedFailure.getValue("status").jsonPrimitive.int, failure.httpStatus, name)
                val detail = expectedFailure["detail"]
                if (detail is JsonObject) assertEquals(
                    detail.getValue("code").jsonPrimitive.content, failure.code, name,
                )
                else assertEquals("LEVEL_TEST_VERIFIER_SCHEMA_INVALID", failure.code, name)
            }
            assertEquals(expected.size, commands.size, name)
            assertTrue(commands.all { it.deadlineUtc == now.plusSeconds(30) }, name)
        }
    }

    @Test
    fun `참고 음성은 채택 후 한 번 합성하여 예약 저장소로 전달한다`() = runBlocking {
        // 준비
        val fixture = fixtures.first { it["name"] == JsonPrimitive("q13_normal") }
        val calls = fixture.getValue("calls").jsonArray.toMutableList()
        val speech = mutableListOf<SpeechSynthesisCommand>()
        val stored = mutableListOf<ByteArray>()
        val bytes = byteArrayOf(1, 2, 3)
        val upload = LevelAudioUpload("http://127.0.0.1/synthetic?token=synthetic", "synthetic.wav")
        val execution = LevelGenerationExecution(
            ModelExecutionPort {
                ModelExecutionResult(
                    calls.removeAt(0).jsonObject.getValue("output"), 7, 4, "test-provider",
                    "synthetic-level-generation",
                )
            },
            SpeechExecutionPort { command ->
                speech += command
                SpeechSynthesisResult(bytes, "audio/wav", "test-provider", "synthetic", 1.2345)
            },
            { destination, audio, mime ->
                assertEquals(upload, destination); assertEquals("audio/wav", mime); stored += audio
            },
            Clock.fixed(now, ZoneOffset.UTC),
        )

        // 실행
        val result = execution.generate(fixture.getValue("request").jsonObject, upload, now.plusSeconds(180))

        // 검증
        assertEquals(1, speech.size)
        assertEquals(1, stored.size)
        assertContentEquals(bytes, stored.single())
        assertEquals("marin", speech.single().voice)
        assertEquals(now.plusSeconds(30), speech.single().deadlineUtc)
        assertEquals(1235, result.referenceAudio?.durationMs)
        assertEquals("synthetic.wav", result.referenceAudio?.objectKey)
    }
}
