package jp.co.translacat.languagelearning.features.writing.domain.model

/** 모델 문맥에 포함할 최근 성공 DAILY 평가의 원본 점수다. */
internal data class WritingRecentScore(
    val overall: Int?,
    val meaning: Int?,
    val grammar: Int?,
    val vocabulary: Int?,
    val naturalness: Int?,
    val expression: Int?,
)
