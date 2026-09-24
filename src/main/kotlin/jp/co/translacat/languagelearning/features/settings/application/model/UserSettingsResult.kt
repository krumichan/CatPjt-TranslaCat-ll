package jp.co.translacat.languagelearning.features.settings.application.model

import jp.co.translacat.languagelearning.features.settings.domain.model.InitialSettingsPolicy
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettings

/** 응답의 허용 범위도 설정을 처리한 동일 트랜잭션의 정책 snapshot을 사용한다. */
internal data class UserSettingsResult(val settings: UserSettings, val policy: InitialSettingsPolicy)
