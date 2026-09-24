package jp.co.translacat.languagelearning.features.listening.domain.model

/** 기존 BE의 선언 순서는 저장 JSON의 정렬 순서이므로 유지한다. */
internal enum class ListeningTaskType {
    DICTATION,
    INTERPRETATION,
    REPEAT_AFTER_AUDIO,
    COMPREHENSION,
    SUMMARY,
}
