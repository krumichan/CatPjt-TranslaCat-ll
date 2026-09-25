package jp.co.translacat.languagelearning.features.leveltest.infrastructure

import jp.co.translacat.languagelearning.features.leveltest.application.LevelTestContext
import jp.co.translacat.languagelearning.features.leveltest.application.LevelTestContextProvider
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations

/** 설정 기능의 Application 포트만 사용한다. 다른 기능의 Exposed 테이블을 직접 조회하지 않는다. */
internal class SettingsLevelTestContext(private val settings: SettingsServiceOperations) : LevelTestContextProvider {
    override suspend fun current(userId: Long): LevelTestContext {
        val user = settings.userSnapshot(userId).result.settings
        val admin = settings.adminPolicy()
        val listening = settings.listeningPolicy()
        return LevelTestContext(
            user.originLanguage, user.learningLanguage, user.timezone, admin.aiEvaluationEnabled,
            admin.resolvedQuestionPoolTarget(), admin.resolvedQuestionPoolReplenishment(),
            listening.profilePolicyVersion, listening.modelConfigVersion, listening.automaticRetryLimit,
        )
    }

    override suspend fun activeLanguagePairs() =
        settings.configuredLanguagePairs().map { it.originLanguage to it.learningLanguage }

    override suspend fun poolPolicy() =
        settings.adminPolicy().let { it.resolvedQuestionPoolReplenishment() to it.resolvedQuestionPoolTarget() }
}
