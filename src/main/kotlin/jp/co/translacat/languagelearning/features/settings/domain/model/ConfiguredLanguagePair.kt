package jp.co.translacat.languagelearning.features.settings.domain.model

/** 문제풀 보충에 필요한 활성 언어쌍만 제공한다. 사용자 목록이나 pending 언어는 노출하지 않는다. */
internal data class ConfiguredLanguagePair(val originLanguage: String, val learningLanguage: String)
