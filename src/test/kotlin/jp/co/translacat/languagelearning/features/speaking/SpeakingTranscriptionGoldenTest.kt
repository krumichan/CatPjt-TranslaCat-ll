package jp.co.translacat.languagelearning.features.speaking

import jp.co.translacat.languagelearning.features.speaking.application.SpeakingTranscriptionPolicy
import jp.co.translacat.languagelearning.shared.ai.AudioDecodeResult
import jp.co.translacat.languagelearning.shared.ai.SpeechTranscriptionResult
import jp.co.translacat.languagelearning.shared.ai.SpeechTranscriptionSegment
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeakingTranscriptionGoldenTest {
    @Test
    fun `원본 Python의 segment 신뢰도와 metadata golden을 보존한다`() {
        // 준비: 원본 Python 서비스에서 생성한 합성 출력만 읽는다.
        val golden = Json.parseToJsonElement(
            checkNotNull(javaClass.getResource("/contracts/speaking-stt-python-golden.json")).readText(),
        ).jsonObject
        val audio = AudioDecodeResult(byteArrayOf(1), 3.125, "wav", .123456, .8, .01, 16000, 1)

        for (item in golden.getValue("cases").jsonArray) {
            val case = item.jsonObject
            val input = case.getValue("input").jsonObject
            val output = SpeechTranscriptionResult(
                input.getValue("text").jsonPrimitive.content, "en",
                input.getValue("languageProbability").jsonPrimitive.double, 3.125,
                input.getValue("segments").jsonArray.map { element ->
                    val segment = element.jsonObject
                    SpeechTranscriptionSegment(
                        segment.getValue("startSeconds").jsonPrimitive.double,
                        segment.getValue("endSeconds").jsonPrimitive.double,
                        segment.getValue("text").jsonPrimitive.content,
                        segment.getValue("avgLogprob").jsonPrimitive.double, null,
                    )
                },
                "synthetic", "fixture", "v1",
            )

            // 실행
            val actual = SpeakingTranscriptionPolicy.transcript(output, audio, "en")

            // 검증
            assertEquals(case.getValue("transcript"), actual, case.getValue("name").jsonPrimitive.content)
        }
    }
}
