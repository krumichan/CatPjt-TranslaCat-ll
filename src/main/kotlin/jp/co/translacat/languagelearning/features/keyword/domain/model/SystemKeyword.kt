package jp.co.translacat.languagelearning.features.keyword.domain.model

internal data class SystemKeyword(
    val id: Long = 0,
    val text: String,
    val normalizedText: String,
    val type: KeywordType,
    val canonicalKey: String?,
    val parentKeywordId: Long?,
    val sortOrder: Int,
    val active: Boolean = true,
)
