package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.leveltest.api.levelTestRoutes
import jp.co.translacat.languagelearning.features.leveltest.application.*
import jp.co.translacat.languagelearning.features.leveltest.infrastructure.SettingsLevelTestContext
import jp.co.translacat.languagelearning.features.leveltest.infrastructure.ai.HttpLevelTestAi
import jp.co.translacat.languagelearning.features.leveltest.infrastructure.persistence.ExposedLevelTestUnitOfWork
import jp.co.translacat.languagelearning.features.leveltest.infrastructure.storage.LocalLevelTestAudioStore
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import kotlinx.coroutines.*
import java.net.URI
import java.nio.file.Path

/** 자동으로 외부 AI나 운영 파일 저장소를 켜지 않는다. 각 환경의 명시적인 활성화가 필요하다. */
internal suspend fun Application.configureLevelTest() {
    fun value(key: String): String? = environment.config.propertyOrNull(key)?.getString()
    fun flag(key: String, default: Boolean) = value(key)?.toBooleanStrict() ?: default
    if (!flag("levelTest.enabled", false)) return
    check(loadInternalApiSettings().enabled) { "레벨 테스트에는 내부 API 인증이 필요합니다." }
    val callback = value("levelTest.audioUploadBaseUrl") ?: error("levelTest.audioUploadBaseUrl을 설정해 주세요.")
    val uri = URI.create(callback)
    require(
        uri.scheme in setOf(
            "http", "https",
        ) && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null,
    ) { "음성 업로드 서버 URL을 확인해 주세요." }
    val aiUrl = value("aiServer.url") ?: error("aiServer.url을 설정해 주세요.")
    val apiKey = value("aiServer.apiKey") ?: error("aiServer.apiKey를 설정해 주세요.")
    val timeout = value("levelTest.aiTimeoutSeconds")?.toLong() ?: 180L
    val concurrency = value("levelTest.aiConcurrency")?.toInt() ?: 2
    require(timeout in 10..300 && concurrency in 1..8) { "레벨 테스트 AI 제한 설정을 확인해 주세요." }
    dependencies { provide<HttpLevelTestAi> { HttpLevelTestAi(aiUrl, apiKey, timeout, concurrency) } }
    val ai = dependencies.resolve<HttpLevelTestAi>()
    val transactions = dependencies.resolve<JdbcTransactionRunner>()
    val context = SettingsLevelTestContext(dependencies.resolve<SettingsServiceOperations>())
    val work = ExposedLevelTestUnitOfWork(transactions)
    val store = LocalLevelTestAudioStore(Path.of(value("levelTest.audioRoot") ?: "data/level-test-audio"))
    val audio = LevelAudioService(work, store, callback)
    val sessions = LevelSessionService(work, context)
    val questions =
        LevelQuestionService(work, context, ai, audio, timeout + 30, flag("levelTest.prefetchEnabled", true))
    val answers = LevelAnswerService(work, context, ai, audio, timeout + 30)
    val reads = LevelReadService(work, audio, ai, context)
    val pool = LevelPoolMaintenance(work, context, ai, audio, timeout + 30)
    routing { levelTestRoutes(work, sessions, questions, answers, reads, audio) }
    val logger = environment.log
    suspend fun repeatSafely(intervalMs: Long, operation: suspend () -> Unit) {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            delay(intervalMs)
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.warn("Level test maintenance failed. type={}", failure.javaClass.simpleName)
            }
        }
    }
    // Application의 생명주기를 따른다. 종료 시 중단되고 미완료 lease는 DB에서 회수한다.
    launch(CoroutineName("level-test-prefetch")) { repeatSafely(2_000) { questions.prefetchOnce() } }
    launch(CoroutineName("level-test-pool")) { repeatSafely(60_000) { pool.refillOnce() } }
    launch(CoroutineName("level-test-audio-retention")) { repeatSafely(60_000) { audio.sweep() } }
    logger.info(
        "Level test internal API initialized. storage=local prefetch={}", flag("levelTest.prefetchEnabled", true),
    )
}
