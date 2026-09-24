package jp.co.translacat.languagelearning.features.keyword.domain.policy

import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordDisplayName
import jp.co.translacat.languagelearning.features.keyword.domain.model.SystemKeywordLocale
import java.util.*

/** learning은 일본어+한국어, ja 계열은 일본어, 나머지는 한국어라는 원본 정책을 유지한다. */
internal object KeywordDisplayPolicy {
    fun resolve(rows: List<SystemKeywordLocale>, uiLocale: String?): Map<Long, KeywordDisplayName> {
        val normalized = KeywordPolicy.trim(uiLocale ?: "").replace('_', '-').lowercase(Locale.ROOT)
        val primary =
            if (normalized == "learning" || normalized == "ja" || normalized.startsWith("ja-")) "ja-JP" else "ko-KR"
        val secondary = if (normalized == "learning") "ko-KR" else null
        return rows.groupBy { it.systemKeywordId }.mapNotNull { (id, locales) ->
            val names = locales.associate { it.locale to it.displayName }
            var first = names[primary]
            var second = secondary?.let(names::get)
            if (first == null) {
                first = second; second = null
            }
            if (first == null) null else id to KeywordDisplayName(first, second?.takeIf { it != first })
        }.toMap()
    }
}
