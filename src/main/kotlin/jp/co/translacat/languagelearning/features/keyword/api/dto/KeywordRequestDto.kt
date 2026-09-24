package jp.co.translacat.languagelearning.features.keyword.api.dto

import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordChange
import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordType
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import kotlinx.serialization.Serializable

@Serializable
internal data class KeywordCreateRequestDto(
    val text: String? = null,
    val type: String? = null,
    val canonicalKey: String? = null,
    val parentKeywordId: Long? = null,
    val sortOrder: Int? = null,
) {
    fun toChange() = KeywordChange(text, keywordType(type), canonicalKey, null, parentKeywordId, sortOrder)
}

@Serializable
internal data class KeywordUpdateRequestDto(
    val text: String? = null,
    val type: String? = null,
    val canonicalKey: String? = null,
    val active: Boolean? = null,
    val parentKeywordId: Long? = null,
    val sortOrder: Int? = null,
) {
    fun toChange() = KeywordChange(text, keywordType(type), canonicalKey, active, parentKeywordId, sortOrder)
}

@Serializable
internal data class SystemKeywordSelectionRequestDto(val selected: Boolean = false)

private fun keywordType(value: String?): KeywordType? =
    if (value == null) null else KeywordType.entries.firstOrNull { it.name == value }
        ?: throw LearningBusinessException("SETTING_INVALID", "Keyword 유형이 유효하지 않습니다.")
