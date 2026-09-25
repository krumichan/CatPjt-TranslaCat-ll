package jp.co.translacat.languagelearning.features.growth.domain.model

import java.time.LocalDate
import java.time.LocalDateTime

/** VocabularyMastery와 다른 개념이다. 사용자 선택 키워드의 숙련도·회전 이력이다. */
internal data class KeywordMastery(
    val userId: Long,
    val canonicalKey: String,
    val score: Double = 50.0,
    val evaluationCount: Int = 0,
    val lastSelectedDate: LocalDate? = null,
    val selectedCount: Int = 0,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)
