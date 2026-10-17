package jp.co.translacat.languagelearning.features.growth.domain.policy

import java.util.Locale

/** 원본 설정값은 보존하고 저장 경계에서만 대소문자와 지역 구분자를 정규화한다. */
internal object GrowthLanguageScope {
    fun key(originLanguage: String, learningLanguage: String): String {
        val origin = originLanguage.trim().replace('_', '-').lowercase(Locale.ROOT)
        val learning = learningLanguage.trim().replace('_', '-').lowercase(Locale.ROOT)
        require(origin.length in 2..20 && learning.length in 2..20)
        // 길이를 포함해 기존 코드에 구분 문자가 있더라도 서로 다른 언어쌍이 충돌하지 않게 한다.
        return "${origin.length}:$origin${learning.length}:$learning"
    }
}
