package jp.co.translacat.languagelearning.features.keyword.domain.model

internal data class SystemKeywordLocale(
    val systemKeywordId: Long,
    val locale: String,
    val displayName: String,
)

internal data class KeywordDisplayName(val primary: String, val secondary: String?)
