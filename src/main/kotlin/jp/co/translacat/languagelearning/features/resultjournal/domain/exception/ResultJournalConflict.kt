package jp.co.translacat.languagelearning.features.resultjournal.domain.exception

/** 순서·중복 식별자 오류는 저장하지 않고 호출자에게 명시적으로 알린다. */
internal class ResultJournalConflict(val code: String) : RuntimeException(code)
