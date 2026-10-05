package jp.co.translacat.languagelearning.features.growth.domain.model

import java.time.LocalDate

/** 저장된 원본의 목록만 읽는다. 점수·원문·새 평가를 이 조회 모델에 복제하지 않는다. */
internal data class LearningEvidenceRecord(
    val id: Long,
    val source: String,
    val learningDate: LocalDate,
    val learningLanguage: String?,
    val originLanguage: String?,
    val title: String,
    val resultKind: String,
    val policyVersion: String?,
    val status: String,
    val resultStatus: String?,
)

internal data class LearningEvidenceCursor(val date: LocalDate, val source: String, val id: Long)

internal data class LearningEvidenceFilter(
    val userId: Long,
    val source: String?,
    val learningLanguage: String?,
    val from: LocalDate,
    val to: LocalDate,
    val resultKind: String?,
    val policyVersion: String?,
    val after: LearningEvidenceCursor?,
    val limit: Int,
)
