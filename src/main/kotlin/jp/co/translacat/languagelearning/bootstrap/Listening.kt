package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.keyword.application.KeywordLearningFacts
import jp.co.translacat.languagelearning.features.keyword.application.KeywordOperations
import jp.co.translacat.languagelearning.features.listening.api.listeningRoutes
import jp.co.translacat.languagelearning.features.listening.application.*
import jp.co.translacat.languagelearning.features.listening.infrastructure.persistence.ExposedListeningUnitOfWork
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.shared.ai.HttpModelExecution
import jp.co.translacat.languagelearning.shared.ai.HttpSpeechExecution
import jp.co.translacat.languagelearning.shared.ai.HttpSpeechTranscription
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.time.Duration
import java.time.ZonedDateTime

internal suspend fun Application.configureListening() {
    fun value(key: String) = environment.config.propertyOrNull(key)?.getString()
    if (value("listening.enabled")?.toBooleanStrictOrNull() != true) return
    check(loadInternalApiSettings().enabled && dependencies.resolve<DatabaseSettings>().enabled)
    val runner = dependencies.resolve<JdbcTransactionRunner>()
    val work = ExposedListeningUnitOfWork(runner)
    val settings = dependencies.resolve<SettingsServiceOperations>()
    val model = dependencies.resolve<HttpModelExecution>()
    val speech = HttpSpeechExecution(checkNotNull(value("aiServer.url")), checkNotNull(value("aiServer.apiKey")))
    val transcription =
        HttpSpeechTranscription(checkNotNull(value("aiServer.url")), checkNotNull(value("aiServer.apiKey")))
    monitor.subscribe(ApplicationStopped) { speech.close(); transcription.close() }
    val context = ListeningContextService(
        settings, dependencies.resolve<KeywordOperations>(), work,
        dependencies.resolve<KeywordLearningFacts>(),
    )
    val reads = ListeningReadService(work, settings)
    val sessions = ListeningSessionService(work, settings)
    val generation = ListeningGenerationWorker(work, settings, ListeningGenerationExecution(model), speech)
    val evaluation = ListeningEvaluationWorker(
        work, settings, ListeningEvaluationExecution(model), ListeningRepeatExecution(transcription),
    )
    val profile = ListeningProfileWorker(work)
    val explanation = ListeningExplanationWorker(work, settings, model)
    routing {
        listeningRoutes(context, reads, sessions, ListeningGenerationService(work, settings))
    }

    // 기존 기능별 동시 작업 수(생성 2·오디오 3·평가 2)를 유지하며 DB가 대기열을 소유한다.
    val slots = mapOf("GENERATE" to Semaphore(2), "TTS" to Semaphore(3), "OTHER" to Semaphore(2))
    val active = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    launch(CoroutineName("listening-job-recovery")) {
        while (isActive) {
            try {
                for (pending in work.read { recoverable(100) }) {
                    val slot = slots.getValue(if (pending.type in slots) pending.type else "OTHER")
                    if (pending.id in active || !slot.tryAcquire()) continue
                    active += pending.id
                    launch {
                        try {
                            when (pending.type) {
                                "GENERATE", "TTS" -> generation.process(pending)
                                "EVALUATE" -> evaluation.process(pending)
                                "PROFILE" -> profile.process(pending)
                                "EXPLANATION" -> explanation.process(pending)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            environment.log.warn("Listening worker failed. type={}", failure.javaClass.simpleName)
                        } finally {
                            active -= pending.id
                            slot.release()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                environment.log.warn("Listening recovery failed. type={}", failure.javaClass.simpleName)
            }
            delay(1_000)
        }
    }

    // 기존 서버 현지 시간 04:35에 LL이 소유하는 만료 음성만 정리한다.
    launch(CoroutineName("listening-audio-retention")) {
        while (isActive) {
            val now = ZonedDateTime.now()
            val next =
                now.withHour(4).withMinute(35).withSecond(0).withNano(0).let { if (it > now) it else it.plusDays(1) }
            delay(Duration.between(now, next).toMillis())
            try {
                while (true) {
                    val users = work.read { audioUsersToExpire(100) }
                    if (users.isEmpty()) break
                    users.forEach { userId -> work.write(userId) { expireAudio(userId) } }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                environment.log.warn("Listening audio retention failed. type={}", failure.javaClass.simpleName)
            }
        }
    }
}
