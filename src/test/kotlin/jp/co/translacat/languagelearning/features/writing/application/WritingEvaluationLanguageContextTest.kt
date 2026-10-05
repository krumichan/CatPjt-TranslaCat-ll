package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.features.writing.domain.model.*
import kotlinx.serialization.json.*
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WritingEvaluationLanguageContextTest {
    private val date = LocalDate.parse("2026-10-04")
    private val item = WritingItem(
        2, 1, 101, 1, WritingDifficulty.NORMAL, "합성 질문", "[]", "[]", "합성 포커스", "[]", "[]", "[]",
    )
    private val answer = WritingAnswer(3, 2, 101, date, "合成の回答です。", WritingEvaluationStatus.PENDING)

    @Test
    fun `설정 변경 뒤에도 저장 과제의 원어와 학습어로 평가한다`() {
        // 준비: 일본어 과제를 저장한 뒤 사용자가 현재 설정을 영어로 바꾼 상황이다.
        val set = storedSet("""{"originLanguage":"ko","learningLanguage":"ja"}""")

        // 실행: 신규 제출과 재개 worker가 받는 현재 언어와 저장 언어를 다르게 둔다.
        val context = WritingEvaluationContextBuilder.build("language-regression", set, item, answer, "en", "fr", date)

        // 검증: Provider metadata와 실제 평가 payload가 모두 같은 원본 언어에 결합된다.
        val payload = Json.parseToJsonElement(context.compactRequestJson).jsonObject
        assertEquals("ko", context.originLanguage)
        assertEquals("ja", context.learningLanguage)
        assertEquals(JsonPrimitive("ko"), payload["originLanguage"])
        assertEquals(JsonPrimitive("ja"), payload["learningLanguage"])
    }

    @Test
    fun `언어가 없는 옛 과제를 현재 언어의 근거로 추정하지 않는다`() {
        // 준비: 없거나 부분적인 metadata는 현재 설정으로 보충할 수 있는 사실이 아니다.
        val snapshots = listOf(
            "{}", """{"originLanguage":"ko"}""",
            """{"originLanguage":"ko","learningLanguage":null}""",
            """{"originLanguage":"ko","learningLanguage":"  "}""",
            """{"originLanguage":"ko","learningLanguage":123}""",
            """{"originLanguage":"ko","learningLanguage":"UNKNOWN"}""",
        )

        for (snapshot in snapshots) {
            // 실행 및 검증: 모델 요청을 만들기 전에 알려지지 않은 원본 언어를 명시적으로 거부한다.
            val error = assertFailsWith<RuntimeException> {
                WritingEvaluationContextBuilder.build("unknown-language", storedSet(snapshot), item, answer, "ko", "en", date)
            }
            assertEquals("WRITING_EVALUATION_LANGUAGE_UNKNOWN", error.message)
        }
    }

    @Test
    fun `알려진 언어 코드는 임의 정규화 없이 원본대로 보존한다`() {
        // 준비: 현재 settings 계약이 보존하는 지역 코드를 과제에도 저장한다.
        val set = storedSet("""{"originLanguage":"ko-KR","learningLanguage":"en-GB"}""")

        // 실행
        val context = WritingEvaluationContextBuilder.build("regional-language", set, item, answer, "ko", "en", date)

        // 검증
        assertEquals("ko-KR", context.originLanguage)
        assertEquals("en-GB", context.learningLanguage)
    }

    private fun storedSet(snapshot: String) = WritingSet(
        1, 101, date, WritingType.FREE, "synthetic", 1, WritingSetStatus.READY,
        snapshot, "v1", 0, null, null, null,
    )
}
