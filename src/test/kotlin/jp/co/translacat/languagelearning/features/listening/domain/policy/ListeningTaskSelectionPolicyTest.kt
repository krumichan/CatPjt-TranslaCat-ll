package jp.co.translacat.languagelearning.features.listening.domain.policy

import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskType
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskType.*
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ListeningTaskSelectionPolicyTest {
    @Test
    fun `32개 모든 조합에서 BE의 8개 허용 집합만 통과한다`() {
        val expected = setOf(
            setOf(DICTATION),
            setOf(REPEAT_AFTER_AUDIO),
            setOf(DICTATION, INTERPRETATION),
            setOf(DICTATION, REPEAT_AFTER_AUDIO),
            setOf(INTERPRETATION, REPEAT_AFTER_AUDIO),
            setOf(DICTATION, INTERPRETATION, REPEAT_AFTER_AUDIO),
            setOf(COMPREHENSION),
            setOf(SUMMARY),
        )
        val values = ListeningTaskType.values()
        for (mask in 0 until (1 shl values.size)) {
            val selected = values.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            if (selected.toSet() in expected) assertEquals(selected, ListeningTaskSelectionPolicy.validate(selected))
            else assertEquals(
                "LISTENING_INVALID_TASK_COMBINATION",
                assertFailsWith<LearningBusinessException> {
                    ListeningTaskSelectionPolicy.validate(selected)
                }.code,
            )
        }
    }

    @Test
    fun `중복과 null을 무시하고 통과시키지 않는다`() {
        for (value in listOf(listOf(DICTATION, DICTATION), listOf(DICTATION, null), listOf(null))) {
            assertFailsWith<LearningBusinessException> { ListeningTaskSelectionPolicy.validate(value) }
        }
    }

    @Test
    fun `정상 입력은 enum 순서의 공백 없는 JSON으로 저장한다`() {
        assertEquals(
            "[\"DICTATION\",\"INTERPRETATION\",\"REPEAT_AFTER_AUDIO\"]",
            ListeningTaskSelectionPolicy.toCanonicalJson(listOf(REPEAT_AFTER_AUDIO, DICTATION, INTERPRETATION)),
        )
    }
}
