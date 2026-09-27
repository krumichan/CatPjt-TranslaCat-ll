package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.keyword.application.KeywordLearningFacts
import jp.co.translacat.languagelearning.features.keyword.application.KeywordOperations
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.writing.api.WritingAnswerRouteContext
import jp.co.translacat.languagelearning.features.writing.api.writingPreparedRoutes
import jp.co.translacat.languagelearning.features.writing.api.writingReportRoutes
import jp.co.translacat.languagelearning.features.writing.application.*
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.ExposedWritingReportQueries
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.ExposedWritingSetUnitOfWork
import jp.co.translacat.languagelearning.shared.ai.HttpModelExecution
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import kotlinx.coroutines.*

/** 명시적으로 켠 환경에서만 Writing 복구 작업자와 모델 실행 경계를 시작한다. */
internal suspend fun Application.configureWriting() {
    fun value(key: String): String? = environment.config.propertyOrNull(key)?.getString()
    if (value("writing.enabled")?.toBooleanStrictOrNull() != true) return
    check(loadInternalApiSettings().enabled && dependencies.resolve<DatabaseSettings>().enabled) {
        "Writing 실행에는 내부 인증과 LL DB가 필요합니다."
    }
    // 모델 호출 전후의 저장은 공유 트랜잭션 runner를 쓰며 HTTP client의 수명은 Ktor가 소유한다.
    val model = dependencies.resolve<HttpModelExecution>()
    val work = ExposedWritingSetUnitOfWork(dependencies.resolve<JdbcTransactionRunner>())
    val generationState = WritingGenerationState(work)
    val evaluationState = WritingEvaluationState(work)
    val generationWorker = WritingGenerationWorker(
        generationState, work, WritingGenerationExecution(model),
        WritingReviewExecution(model), note = WritingNoteLocalizationExecution(model),
        sourceRecovery = WritingSourceRecoveryExecution(model), regeneration = WritingRegenerationState(work),
    )
    val evaluationWorker = WritingEvaluationWorker(evaluationState, WritingEvaluationExecution(model))
    val generationRecovery = WritingGenerationRecovery(generationState, generationWorker)
    val settings = dependencies.resolve<SettingsServiceOperations>()
    val context = WritingContextService(
        settings, dependencies.resolve<KeywordOperations>(), work,
        dependencies.resolve<KeywordLearningFacts>(),
    )
    val read = WritingReadService(work)
    val reports = WritingReportService(ExposedWritingReportQueries(dependencies.resolve<JdbcTransactionRunner>()))
    routing {
        writingReportRoutes(reports, settings::learningDate)
        writingPreparedRoutes(
            generationState, generationWorker, read,
            readContext = { userId ->
                settings.learningDate(userId) to settings.adminPolicy().reviewAvailableDays
            },
            answers = WritingAnswerState(work), evaluation = evaluationWorker,
            answerContext = { userId ->
                val snapshot = settings.userSnapshot(userId)
                val user = snapshot.result.settings
                val admin = settings.adminPolicy()
                WritingAnswerRouteContext(
                    snapshot.learningDate, admin.reviewAvailableDays,
                    admin.aiEvaluationEnabled,
                    checkNotNull(user.originLanguage) { "WRITING_ORIGIN_LANGUAGE_MISSING" },
                    checkNotNull(user.learningLanguage) { "WRITING_LEARNING_LANGUAGE_MISSING" },
                )
            },
            regenerationAllowed = { settings.adminPolicy().adaptiveWritingEnabled },
            createSet = context::getOrCreate,
        )
    }
    val evaluationRecovery = WritingEvaluationRecovery(evaluationState, evaluationWorker) { userId ->
        val snapshot = settings.userSnapshot(userId)
        val user = snapshot.result.settings
        WritingEvaluationLanguageContext(
            checkNotNull(user.originLanguage) { "WRITING_ORIGIN_LANGUAGE_MISSING" },
            checkNotNull(user.learningLanguage) { "WRITING_LEARNING_LANGUAGE_MISSING" },
            snapshot.learningDate,
        )
    }
    val logger = environment.log

    // 재기동 시 만료 lease를 한 번 확인하고, 이후 짧은 주기로 새 PENDING 작업을 확인한다.
    launch(CoroutineName("writing-generation-recovery")) {
        while (coroutineContext.isActive) {
            try {
                generationRecovery.runOnce()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.warn("Writing generation recovery failed. type={}", failure.javaClass.simpleName)
            }
            delay(30_000)
        }
    }
    launch(CoroutineName("writing-evaluation-recovery")) {
        while (coroutineContext.isActive) {
            try {
                evaluationRecovery.runOnce()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.warn("Writing evaluation recovery failed. type={}", failure.javaClass.simpleName)
            }
            delay(30_000)
        }
    }
    logger.info("Writing recovery workers initialized")
}
