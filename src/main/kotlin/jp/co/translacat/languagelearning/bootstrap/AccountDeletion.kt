package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.accountdeletion.api.accountDeletionRoutes
import jp.co.translacat.languagelearning.features.accountdeletion.application.*
import jp.co.translacat.languagelearning.features.leveltest.application.LevelTestAudioStore
import jp.co.translacat.languagelearning.features.speaking.application.SpeakingAudioStore
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingExecutionCache
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.CuratedWritingFeedbackWorker
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings

internal suspend fun Application.configureAccountDeletion() {
    if (!loadInternalApiSettings().enabled || !dependencies.resolve<DatabaseSettings>().enabled) return
    fun enabled(name: String) = environment.config.propertyOrNull(name)?.getString()?.toBooleanStrictOrNull() == true
    val level = if (enabled("levelTest.enabled")) dependencies.resolve<LevelTestAudioStore>() else null
    val speaking = if (enabled("speaking.enabled")) dependencies.resolve<SpeakingAudioStore>() else null
    val store = dependencies.resolve<AccountDeletionStore>()
    val feedback = if (enabled("writing.curated.feedback.enabled")) dependencies.resolve<CuratedWritingFeedbackWorker>() else null
    val service = AccountDeletionService(store, deleteObject = { target ->
        when (target.kind) {
            "LEVEL_TEST" -> checkNotNull(level).delete(target.key)
            "SPEAKING" -> checkNotNull(speaking).delete(target.key)
        }
    }, clearMemory = { user, sessions ->
        SpeakingExecutionCache.discardSessions(sessions.toSet())
        feedback?.discardUser(user)
    })

    // 과거 파일이 남을 수 있는 두 저장소가 모두 구성되어야 삭제 접수를 허용한다.
    routing { accountDeletionRoutes(service) { level != null && speaking != null } }
}
