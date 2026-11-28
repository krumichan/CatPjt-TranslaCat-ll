package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.autopractice.api.autoPracticeRoutes
import jp.co.translacat.languagelearning.features.autopractice.application.AutoPracticeAccess
import jp.co.translacat.languagelearning.features.autopractice.application.AutoPracticePublisher
import jp.co.translacat.languagelearning.features.autopractice.application.AutoPracticeService
import jp.co.translacat.languagelearning.features.autopractice.domain.AutoPracticeArea
import jp.co.translacat.languagelearning.features.autopractice.domain.AutoPracticeJob
import jp.co.translacat.languagelearning.features.autopractice.infrastructure.JdbcAutoPracticeStore
import jp.co.translacat.languagelearning.features.keyword.application.KeywordLearningFacts
import jp.co.translacat.languagelearning.features.keyword.application.KeywordOperations
import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.repository.ExposedLearnerRepository
import jp.co.translacat.languagelearning.features.listening.application.ListeningContextService
import jp.co.translacat.languagelearning.features.listening.infrastructure.persistence.ExposedListeningUnitOfWork
import jp.co.translacat.languagelearning.features.practice.application.PracticeContextService
import jp.co.translacat.languagelearning.features.practice.domain.PracticeDomain
import jp.co.translacat.languagelearning.features.practice.infrastructure.ExposedPracticeUnitOfWork
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.writing.application.WritingContextService
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.ExposedWritingSetUnitOfWork
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import kotlinx.coroutines.*

/** 새 프로바이더를 등록하지 않고 활성화된 각 영역의 기존 생성 작업에 예약을 보낸다. */
internal suspend fun Application.configureAutoPractice() {
    if (!loadInternalApiSettings().enabled || !dependencies.resolve<DatabaseSettings>().enabled) return
    val settings = dependencies.resolve<SettingsServiceOperations>()
    val keywords = dependencies.resolve<KeywordOperations>()
    val facts = dependencies.resolve<KeywordLearningFacts>()
    val runner = dependencies.resolve<JdbcTransactionRunner>()
    fun enabled(name: String) = environment.config.propertyOrNull("$name.enabled")?.getString() == "true"

    // 기존 worker가 복구·검증·TTS를 소유한다. 새 스케줄러는 최초 세트의 배정만 책임진다.
    val publishers = buildMap<AutoPracticeArea, AutoPracticePublisher> {
        if (enabled("writing")) {
            val context = WritingContextService(settings, keywords, ExposedWritingSetUnitOfWork(runner), facts)
            AutoPracticeArea.entries.filter { it.isWriting }.forEach { area ->
                val type = WritingType.valueOf(area.name.removePrefix("WRITING_"))
                put(area, NativeAutoPracticePublisher(
                    { owner, job -> context.getOrCreate(owner, type, job).id }, context::preparedStatus,
                ))
            }
        }
        if (enabled("practice")) {
            val context = PracticeContextService(settings, keywords, ExposedPracticeUnitOfWork(runner), facts)
            AutoPracticeArea.entries.filter { it.name.startsWith("READING_") }.forEach { area ->
                val mode = area.name.removePrefix("READING_")
                put(area, NativeAutoPracticePublisher(
                    { owner, job -> context.today(owner, PracticeDomain.READING, mode, job).id }, context::preparedStatus,
                ))
            }
        }
        if (enabled("listening")) {
            val context = ListeningContextService(settings, keywords, ExposedListeningUnitOfWork(runner), facts)
            AutoPracticeArea.entries.filter { it.name.startsWith("LISTENING_") }.forEach { area ->
                val mode = area.name.removePrefix("LISTENING_")
                put(area, NativeAutoPracticePublisher(
                    { owner, job ->
                        context.getOrCreate(owner, mode, itemCount = job.quantity.total.toInt(), scheduled = job).id
                    },
                    context::preparedStatus,
                ))
            }
        }
    }
    val store = JdbcAutoPracticeStore(runner) { user, now ->
        ExposedLearnerRepository {}.ensureAndLock(user, now).requireActive()
    }
    dependencies { provide<AutoPracticeAccess> { AutoPracticeAccess(store) } }
    val service = AutoPracticeService(store, settings, publishers)
    routing { autoPracticeRoutes(service) }

    launch(CoroutineName("automatic-practice-scheduler")) {
        while (isActive) {
            try {
                service.tick { failure ->
                    environment.log.warn("Automatic practice item failed. type={}", failure.javaClass.simpleName)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                environment.log.warn("Automatic practice tick failed. type={}", failure.javaClass.simpleName)
            }
            delay(30_000)
        }
    }
}

/** 기존 클라이언트 REST와 동일한 공개 식별자를 반환한다. */
private class NativeAutoPracticePublisher(
    private val create: suspend (Long, AutoPracticeJob) -> Long,
    private val readStatus: suspend (Long, Long) -> String,
) : AutoPracticePublisher {
    override suspend fun enqueue(owner: Long, job: AutoPracticeJob): String =
        LearningPublicId.encode(create(owner, job)).toString()

    override suspend fun state(owner: Long, job: AutoPracticeJob): String =
        readStatus(owner, LearningPublicId.decode(checkNotNull(job.resourceId)))
}
