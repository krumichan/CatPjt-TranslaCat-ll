package jp.co.translacat.languagelearning.features.keyword.domain.model

/** null인 부모 ID는 기존 BE와 동일하게 부모 연결 해제를 뜻한다. 누락도 null로 처리한다. */
internal data class KeywordChange(
    val text: String? = null,
    val type: KeywordType? = null,
    val canonicalKey: String? = null,
    val active: Boolean? = null,
    val parentKeywordId: Long? = null,
    val sortOrder: Int? = null,
)
