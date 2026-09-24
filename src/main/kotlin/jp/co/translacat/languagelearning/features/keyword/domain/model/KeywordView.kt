package jp.co.translacat.languagelearning.features.keyword.domain.model

import java.time.LocalDate

internal data class KeywordView(
    val id: Long,
    val text: String,
    val displayName: String?,
    val secondaryDisplayName: String?,
    val source: KeywordSource,
    val type: KeywordType,
    val canonicalKey: String?,
    val parentKeywordId: Long?,
    val parentCanonicalKey: String?,
    val sortOrder: Int,
    val active: Boolean,
    val selected: Boolean,
    val pendingEffectiveDate: LocalDate?,
)

internal data class KeywordList(val systemKeywords: List<KeywordView>, val customKeywords: List<KeywordView>)
