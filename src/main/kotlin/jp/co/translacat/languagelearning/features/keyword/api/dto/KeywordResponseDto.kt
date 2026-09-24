package jp.co.translacat.languagelearning.features.keyword.api.dto

import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordCandidate
import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordList
import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordView
import kotlinx.serialization.Serializable

@Serializable
internal data class KeywordResponseDto(
    val id: Long,
    val text: String,
    val displayName: String?,
    val secondaryDisplayName: String?,
    val source: String,
    val type: String,
    val canonicalKey: String?,
    val parentKeywordId: Long?,
    val parentCanonicalKey: String?,
    val sortOrder: Int,
    val active: Boolean,
    val selected: Boolean,
    val pendingEffectiveDate: String?,
)

@Serializable
internal data class KeywordListResponseDto(
    val systemKeywords: List<KeywordResponseDto>, val customKeywords: List<KeywordResponseDto>
)

@Serializable
internal data class KeywordCandidateDto(
    val key: String,
    val text: String,
    val source: String,
    val type: String,
    val canonicalKey: String?,
    val availableFrom: String,
)

@Serializable
internal data class KeywordCandidatesDto(val candidates: List<KeywordCandidateDto>)

internal fun KeywordView.toResponse() = KeywordResponseDto(
    id, text, displayName, secondaryDisplayName, source.name, type.name, canonicalKey,
    parentKeywordId, parentCanonicalKey, sortOrder, active, selected, pendingEffectiveDate?.toString(),
)

internal fun KeywordList.toResponse() =
    KeywordListResponseDto(systemKeywords.map { it.toResponse() }, customKeywords.map { it.toResponse() })

internal fun KeywordCandidate.toResponse() =
    KeywordCandidateDto(key, text, source.name, type.name, canonicalKey, availableFrom.toString())
