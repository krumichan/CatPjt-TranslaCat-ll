package jp.co.translacat.languagelearning.features.settings.domain.model

/** DB에 저장된 현재 정책이다. 사용자별 기본 목표 수를 코드에 중복 정의하지 않는다. */
internal data class GoalPolicy(
    val defaultValue: Int,
    val minimum: Int,
    val maximum: Int,
) {
    init {
        require(minimum in 1..maximum && defaultValue in minimum..maximum) {
            "설정 기본값과 허용 범위를 확인해 주세요."
        }
    }
}

internal data class InitialSettingsPolicy(
    val writing: GoalPolicy,
    val speaking: GoalPolicy,
    val listening: GoalPolicy,
)
