package jp.co.translacat.languagelearning.features.keyword.domain.model

import java.time.LocalDate

/** 숙련도 가중치와 선택 횟수는 평가 트랜잭션을 소유한 BE가 계산한다. */
internal data class KeywordCandidate(
    val key: String,
    val text: String,
    val source: KeywordSource,
    val type: KeywordType,
    val canonicalKey: String?,
    val availableFrom: LocalDate,
)
