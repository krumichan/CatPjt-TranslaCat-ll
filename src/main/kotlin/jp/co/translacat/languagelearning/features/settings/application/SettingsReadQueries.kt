package jp.co.translacat.languagelearning.features.settings.application

import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningPolicy
import jp.co.translacat.languagelearning.features.settings.domain.model.ConfiguredLanguagePair

/** 생성·승격 없이 저장된 값만 읽는 포트다. 조회 누락을 로컬 기본 정책으로 대체하지 않는다. */
internal interface SettingsReadQueries {
    suspend fun findTimezone(userId: Long): String?
    suspend fun listeningPolicy(): ListeningPolicy
    suspend fun configuredLanguagePairs(): List<ConfiguredLanguagePair>
}
