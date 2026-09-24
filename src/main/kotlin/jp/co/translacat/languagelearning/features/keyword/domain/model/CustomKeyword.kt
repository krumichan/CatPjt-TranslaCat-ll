package jp.co.translacat.languagelearning.features.keyword.domain.model

import java.time.LocalDate

/** 저장 상태와 예약 상태를 분리한다. 조회 화면은 desired 값, 문제 생성은 active 값을 사용한다. */
internal data class CustomKeyword(
    val id: Long = 0,
    val userId: Long,
    val text: String,
    val normalizedText: String,
    val type: KeywordType,
    val canonicalKey: String?,
    val parentSystemKeywordId: Long?,
    val active: Boolean,
    val availableFrom: LocalDate,
    val pendingText: String? = null,
    val pendingNormalizedText: String? = null,
    val pendingType: KeywordType? = null,
    val pendingCanonicalKey: String? = null,
    val pendingParentSystemKeywordId: Long? = null,
    val pendingParentChanged: Boolean = false,
    val pendingActive: Boolean? = null,
    val pendingEffectiveDate: LocalDate? = null,
) {
    val desiredText: String get() = pendingText ?: text
    val desiredNormalizedText: String get() = pendingNormalizedText ?: normalizedText
    val desiredType: KeywordType get() = pendingType ?: type
    val desiredCanonicalKey: String? get() = pendingCanonicalKey ?: canonicalKey
    val desiredActive: Boolean get() = pendingActive ?: active
    val desiredParentSystemKeywordId: Long?
        get() = if (pendingParentChanged) pendingParentSystemKeywordId else parentSystemKeywordId

    fun promote(date: LocalDate, immediately: Boolean = false): CustomKeyword {
        val effective = pendingEffectiveDate ?: return this
        if (!immediately && effective.isAfter(date)) return this
        return copy(
            text = desiredText,
            normalizedText = desiredNormalizedText,
            type = desiredType,
            canonicalKey = desiredCanonicalKey,
            parentSystemKeywordId = desiredParentSystemKeywordId,
            active = desiredActive,
            pendingText = null,
            pendingNormalizedText = null,
            pendingType = null,
            pendingCanonicalKey = null,
            pendingParentSystemKeywordId = null,
            pendingParentChanged = false,
            pendingActive = null,
            pendingEffectiveDate = null,
        )
    }

    fun schedule(
        text: String, normalizedText: String, type: KeywordType, canonicalKey: String?,
        parentSystemKeywordId: Long?, active: Boolean?, effectiveDate: LocalDate,
    ): CustomKeyword = copy(
        pendingText = text,
        pendingNormalizedText = normalizedText,
        pendingType = type,
        pendingCanonicalKey = canonicalKey,
        pendingParentSystemKeywordId = parentSystemKeywordId,
        pendingParentChanged = true,
        pendingActive = active ?: desiredActive,
        pendingEffectiveDate = effectiveDate,
    )
}
