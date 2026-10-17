package jp.co.translacat.languagelearning.features.settings.domain.model

/** 활성 언어쌍과 저장 원본의 일치를 확인한다. 사용자 목록이나 pending 언어는 노출하지 않는다. */
internal data class ConfiguredLanguagePair(val originLanguage: String, val learningLanguage: String) {
    fun matches(origin: String?, learning: String?): Boolean =
        normalized(originLanguage) == normalized(origin) && normalized(learningLanguage) == normalized(learning)

    // 지역 태그는 보존하고 표기 차이만 정규화한다. 누락 언어를 현재 언어로 채우지 않는다.
    private fun normalized(value: String?): String? = value?.trim()?.replace('_', '-')?.lowercase()

    companion object {
        // 저장 원본에 언어 근거가 없으면 이력 필터가 현재 언어로 추정하지 않게 null을 전달한다.
        fun knownLanguage(value: String?): String? = value?.takeUnless {
            it.isBlank() || it.equals("UNKNOWN", true) || it.equals("und", true)
        }
    }
}
