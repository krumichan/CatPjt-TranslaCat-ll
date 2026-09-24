package jp.co.translacat.languagelearning.features.settings.domain.exception

/** 필수 기준 설정이 없으면 임의의 기본값을 만들지 않고 실패한다. */
internal class SettingsPolicyNotInitializedException(policy: String) :
    IllegalStateException("필수 설정 행이 없습니다. migration과 설정 데이터를 확인해 주세요: $policy")
