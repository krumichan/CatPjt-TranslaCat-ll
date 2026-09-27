package jp.co.translacat.languagelearning.features.writing.api

import jp.co.translacat.languagelearning.shared.http.InternalApiError

/** 기존 BE BusinessException의 외부 코드와 안내를 유지한다. */
internal fun writingStateError(code: String): InternalApiError? {
    val contract = when (code) {
        "WRITING_ITEM_NOT_FOUND" -> "DAILY_ITEM_NOT_FOUND" to "Daily Item을 찾을 수 없습니다."
        "WRITING_SET_NOT_FOUND" -> "DAILY_SET_NOT_FOUND" to "Daily Set을 찾을 수 없습니다."
        "WRITING_ANSWER_NOT_FOUND" -> "ANSWER_NOT_ALLOWED" to "Writing Answer를 찾을 수 없습니다."
        "WRITING_ITEM_STALE" -> "WRITING_ITEM_STALE" to "문제가 재생성되었습니다. 최신 문제를 확인한 뒤 다시 제출해주세요."
        "WRITING_REGENERATION_IN_PROGRESS" -> "WRITING_REGENERATION_IN_PROGRESS" to "문제 재생성이 진행 중입니다. 완료 후 다시 제출해주세요."
        "WRITING_ANSWER_NOT_ALLOWED" -> "ANSWER_NOT_ALLOWED" to "동일 문제는 하루에 한 번만 제출할 수 있습니다."
        "WRITING_SET_GENERATING" -> "DAILY_SET_GENERATING" to "생성 중이거나 일부만 생성된 문제는 생성 재시도를 이용해주세요."
        "WRITING_REGENERATION_LIMIT" -> "REGENERATION_LIMIT" to "문제 재생성 가능 횟수를 초과했습니다."
        "WRITING_NO_UNANSWERED_ITEM" -> "ANSWER_NOT_ALLOWED" to "재생성 가능한 미응답 문제가 없습니다."
        "WRITING_REGENERATION_CONFLICT" -> "WRITING_REGENERATION_CONFLICT" to "재생성 중 문항 상태가 변경되었습니다."
        "WRITING_EVALUATION_DISABLED" -> "SETTING_INVALID" to "AI Writing 평가가 비활성화되어 있습니다."
        "WRITING_GENERATION_DISABLED" -> "SETTING_INVALID" to "AI Writing 생성이 비활성화되어 있습니다."
        "WRITING_ANSWER_REQUIRED" -> "ANSWER_NOT_ALLOWED" to "답변이 필요합니다."
        "WRITING_REVIEW_EXPIRED" -> "REVIEW_EXPIRED" to "재학습 가능 기간이 지났습니다."
        else -> return null
    }
    return InternalApiError("LANGUAGE_LEARNING_${contract.first}", contract.second)
}
