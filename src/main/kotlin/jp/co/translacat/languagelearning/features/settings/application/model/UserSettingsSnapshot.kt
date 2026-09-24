package jp.co.translacat.languagelearning.features.settings.application.model

import java.time.LocalDate

/** 승격·보정된 설정과 그 설정의 활성 timezone으로 산정한 날짜를 함께 고정한다. */
internal data class UserSettingsSnapshot(
    val userId: Long,
    val learningDate: LocalDate,
    val result: UserSettingsResult,
)
