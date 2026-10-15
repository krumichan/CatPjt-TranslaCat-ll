package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.keyword.application.KeywordLearningFacts
import jp.co.translacat.languagelearning.features.keyword.application.KeywordOperations
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.webaiplan.api.webAiRoutes
import jp.co.translacat.languagelearning.features.webaiplan.application.*
import jp.co.translacat.languagelearning.features.webaiplan.infrastructure.ExposedWebAiStore
import jp.co.translacat.languagelearning.features.webaiplan.domain.model.WebAiException
import jp.co.translacat.languagelearning.shared.ai.*
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal suspend fun Application.configureWebAi() {
    if (!loadInternalApiSettings().enabled) return
    val store = ExposedWebAiStore(dependencies.resolve<JdbcTransactionRunner>())
    val context = SettingsWebAiContextProvider(
        dependencies.resolve<SettingsServiceOperations>(), dependencies.resolve<KeywordOperations>(),
        dependencies.resolve<KeywordLearningFacts>(),
    )

    // 설정·계획 저장은 AI Provider와 독립적으로 제공한다. Provider 미설정은 검증 실패로 보존 후 재시도할 수 있다.
    val modelEnabled = listOf("writing", "practice", "listening", "speaking", "levelTest").any {
        environment.config.propertyOrNull("$it.enabled")?.getString()?.toBooleanStrictOrNull() == true
    }
    val model: ModelExecutionPort = if (modelEnabled) dependencies.resolve<HttpModelExecution>() else
        ModelExecutionPort { throw ModelExecutionFailure("WEB_AI_VERIFICATION_UNAVAILABLE", 503, false) }
    val verification = WebAiVerificationExecution(model)
    val service = WebAiPlanService(store, context, { input ->
        verification.verify(input)
    }, asynchronous = true)
    routing { webAiRoutes(service) }

    // HTTP 연결과 분리된 DB 대기열이다. 재기동 후에도 원문·완료 문항을 보존하며 만료 lease를 복구한다.
    val slots = Semaphore(2)
    val activeUsers = ConcurrentHashMap.newKeySet<Long>()
    launch(CoroutineName("web-ai-verification-recovery")) {
        while (isActive) {
            try {
                for ((userId, resultId) in store.pendingCandidates(Instant.now(), 100)) {
                    if (userId in activeUsers || !slots.tryAcquire()) continue
                    activeUsers += userId
                    launch {
                        try {
                            service.processQueued(userId, resultId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: WebAiException) {
                            // 다른 인스턴스가 먼저 lease를 얻은 경우는 정상적인 경합이다.
                            if (failure.code !in setOf("WEB_AI_CONFLICT", "WEB_AI_VERIFICATION_BUSY")) {
                                environment.log.warn("Web AI verification worker rejected. code={}", failure.code)
                            }
                        } catch (failure: Exception) {
                            // 토큰·문제·답안·Provider 원문은 작업 로그에 넣지 않는다.
                            environment.log.warn("Web AI verification worker failed. type={}", failure.javaClass.simpleName)
                        } finally {
                            activeUsers -= userId
                            slots.release()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                environment.log.warn("Web AI recovery failed. type={}", failure.javaClass.simpleName)
            }
            delay(2_000)
        }
    }
}
