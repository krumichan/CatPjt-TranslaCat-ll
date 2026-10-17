package jp.co.translacat.languagelearning.features.keyword.domain.policy

import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordDisplayName
import jp.co.translacat.languagelearning.features.keyword.domain.model.SystemKeywordLocale
import jp.co.translacat.languagelearning.features.keyword.domain.model.SystemKeyword
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.*

/** UI 언어는 그대로 유지하고 learning 표시만 사용자의 실제 학습·원문 언어를 따른다. */
internal object KeywordDisplayPolicy {
    private val englishLabels by lazy {
        checkNotNull(javaClass.getResourceAsStream("/keyword/english-labels.json"))
            .bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
            .mapValues { it.value.jsonPrimitive.content }
    }

    fun resolve(
        rows: List<SystemKeywordLocale>, uiLocale: String?,
        originLanguage: String? = null, learningLanguage: String? = null,
        catalog: List<SystemKeyword> = emptyList(),
    ): Map<Long, KeywordDisplayName> {
        // 설정 전 호출의 기존 한일 표시를 보존하며 영어 학습에서는 영어 표시명을 우선한다.
        val normalized = KeywordPolicy.trim(uiLocale ?: "").replace('_', '-').lowercase(Locale.ROOT)
        val mixed = normalized == "learning"
        val primary = if (mixed) locale(learningLanguage) ?: "ja-JP" else locale(normalized) ?: "ko-KR"
        val secondary = if (mixed) locale(originLanguage) ?: "ko-KR" else null

        // 새 영어 자료가 아직 없는 사용자 정의 카탈로그도 원문 표시명으로 읽을 수 있게 한다.
        val english = catalog.mapNotNull { keyword ->
            englishLabels[keyword.canonicalKey?.uppercase(Locale.ROOT)]?.let { keyword.id to it }
        }.toMap()
        val grouped = rows.groupBy { it.systemKeywordId }
        return (grouped.keys + english.keys).mapNotNull { id ->
            val names = grouped[id].orEmpty().associate { it.locale to it.displayName }.toMutableMap()
            english[id]?.let { names.putIfAbsent("en-US", it) }
            var first = names[primary]
            var second = secondary?.let(names::get)
            if (first == null) {
                first = second; second = null
            }
            if (first == null && primary == "en-US") first = names["ko-KR"]
            if (first == null) null else id to KeywordDisplayName(first, second?.takeIf { it != first })
        }.toMap()
    }

    private fun locale(language: String?): String? = when (
        language?.trim()?.replace('_', '-')?.substringBefore('-')?.lowercase(Locale.ROOT)
    ) {
        "ko" -> "ko-KR"
        "ja" -> "ja-JP"
        "en" -> "en-US"
        else -> null
    }
}
