package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.keyword.application.KeywordLearningFacts
import jp.co.translacat.languagelearning.features.keyword.application.KeywordOperations
import jp.co.translacat.languagelearning.features.practice.api.practiceRoutes
import jp.co.translacat.languagelearning.features.practice.application.*
import jp.co.translacat.languagelearning.features.practice.infrastructure.ExposedPracticeUnitOfWork
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.shared.ai.HttpModelExecution
import jp.co.translacat.languagelearning.shared.ai.ModelTier
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import kotlinx.coroutines.*

internal suspend fun Application.configurePractice() {
    fun setting(key: String) = environment.config.propertyOrNull(key)?.getString()
    if (setting("practice.enabled")?.toBooleanStrictOrNull() != true) return
    check(loadInternalApiSettings().enabled && dependencies.resolve<DatabaseSettings>().enabled)

    // 현재 AI 설정에 맞는 모델 tier와 공유 HTTP 실행 객체를 연결한다. SDK나 업무 Python 함수는 호출하지 않는다.
    val model = dependencies.resolve<HttpModelExecution>()
    val runner = dependencies.resolve<JdbcTransactionRunner>()
    val work = ExposedPracticeUnitOfWork(runner)
    val settings = dependencies.resolve<SettingsServiceOperations>()
    val context = PracticeContextService(
        settings, dependencies.resolve<KeywordOperations>(), work,
        dependencies.resolve<KeywordLearningFacts>(),
    )
    val state = PracticeGenerationState(work)
    val worker = PracticeGenerationWorker(
        state,
        ReadingGenerationExecution(
            model,
            ModelTier.valueOf(setting("practice.generationTier") ?: "SOL"),
        ),
    )
    routing {
        practiceRoutes(context, PracticeReadService(work), PracticeAnswerService(work), state, settings::learningDate)
    }

    // 영속 PENDING/만료 lease를 기존 1초 주기로 조회하므로 재기동 후에도 같은 작업을 이어간다.
    launch(CoroutineName("practice-generation-recovery")) {
        while (isActive) {
            try {
                worker.recover()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                environment.log.warn("Practice recovery failed. type={}", failure.javaClass.simpleName)
            }
            delay(1000)
        }
    }
}
