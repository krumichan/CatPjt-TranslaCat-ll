package jp.co.translacat.languagelearning.features.listening.domain.policy

import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskType
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskType.*
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException

/** 기존 BE의 validate 규칙이다. INTERPRETATION 단독과 중복 선택은 허용하지 않는다. */
internal object ListeningTaskSelectionPolicy {
    private val allowed = setOf(
        setOf(DICTATION), setOf(REPEAT_AFTER_AUDIO),
        setOf(DICTATION, INTERPRETATION), setOf(DICTATION, REPEAT_AFTER_AUDIO),
        setOf(INTERPRETATION, REPEAT_AFTER_AUDIO),
        setOf(DICTATION, INTERPRETATION, REPEAT_AFTER_AUDIO),
        setOf(COMPREHENSION), setOf(SUMMARY),
    )

    fun validate(requested: List<ListeningTaskType?>): List<ListeningTaskType> {
        val selected = requested.filterNotNull()
        if (selected.isEmpty() || selected.size != requested.size || selected.toSet().size != selected.size || selected.toSet() !in allowed) {
            throw LearningBusinessException(
                "LISTENING_INVALID_TASK_COMBINATION", "Listening Task 선택 조합이 허용되지 않습니다.",
            )
        }
        return selected.sortedBy { it.ordinal }
    }

    // 값은 검증된 enum 이름뿐이므로 문자열 이스케이프가 필요한 외부 입력은 들어오지 않는다.
    fun toCanonicalJson(requested: List<ListeningTaskType?>): String =
        validate(requested).joinToString(separator = ",", prefix = "[", postfix = "]") { "\"${it.name}\"" }
}
