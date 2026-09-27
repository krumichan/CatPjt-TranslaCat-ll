package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.keyword.application.KeywordLearningFacts
import jp.co.translacat.languagelearning.features.keyword.application.KeywordOperations
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.speaking.api.speakingRoutes
import jp.co.translacat.languagelearning.features.speaking.application.*
import jp.co.translacat.languagelearning.features.speaking.execution.*
import jp.co.translacat.languagelearning.features.speaking.infrastructure.ExposedSpeakingUnitOfWork
import jp.co.translacat.languagelearning.features.speaking.infrastructure.LocalSpeakingAudioStore
import jp.co.translacat.languagelearning.features.speaking.infrastructure.S3SpeakingAudioStore
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.ExposedWritingReportQueries
import jp.co.translacat.languagelearning.shared.ai.HttpModelExecution
import jp.co.translacat.languagelearning.shared.ai.HttpSpeechExecution
import jp.co.translacat.languagelearning.shared.ai.HttpSpeechTranscription
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.JsonNull
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

internal suspend fun Application.configureSpeaking() {
    fun value(key: String) = environment.config.propertyOrNull(key)?.getString()
    fun required(key: String) =
        checkNotNull(value(key)?.takeIf(String::isNotBlank)) { "Missing Speaking configuration: $key" }
    if (value("speaking.enabled")?.toBooleanStrictOrNull() != true) return
    check(loadInternalApiSettings().enabled && dependencies.resolve<DatabaseSettings>().enabled)

    // 명시된 저장소와 공통 기술 HTTP만 연결한다. 기본 AWS credential chain이나 Python 업무 API는 사용하지 않는다.
    val audioStore: SpeakingAudioStore = when (required("speaking.audio.storage")) {
        "local" -> LocalSpeakingAudioStore(Path.of(required("speaking.audio.directory")))
        "s3" -> S3SpeakingAudioStore(
            URI(required("speaking.audio.endpoint")), required("speaking.audio.region"),
            required("speaking.audio.bucket"), required("speaking.audio.accessKey"),
            required("speaking.audio.secretKey"),
        )

        else -> error("Unsupported Speaking audio storage")
    }
    val synthesis = HttpSpeechExecution(required("aiServer.url"), required("aiServer.apiKey"))
    val transcription = HttpSpeechTranscription(required("aiServer.url"), required("aiServer.apiKey"))
    monitor.subscribe(ApplicationStopped) {
        synthesis.close()
        transcription.close()
        (audioStore as? AutoCloseable)?.close()
    }

    // 사용자별 상태와 원본 결과 조회를 같은 LL 저장소에 연결하고 기존 문맥 준비 순서를 유지한다.
    val model = dependencies.resolve<HttpModelExecution>()
    val runner = dependencies.resolve<JdbcTransactionRunner>()
    val work = ExposedSpeakingUnitOfWork(runner)
    val settings = dependencies.resolve<SettingsServiceOperations>()
    val reads = SpeakingReadService(work, settings)
    val results = SpeakingResultApplication(work)
    val reports = SpeakingReportService(
        work, settings, reads, { user, session -> results.evaluation(user, session) ?: JsonNull },
        ExposedWritingReportQueries(runner),
    )
    val context = SpeakingContextService(
        settings, dependencies.resolve<KeywordOperations>(), work,
        dependencies.resolve<KeywordLearningFacts>(), { reports.recommendedFocus(it) },
    )
    val speech = SpeakingSpeechExecution(transcription, synthesis, required("speaking.ttsModel"))
    val conversation = SpeakingConversationExecution(model)
    val sessions = SpeakingSessionState(work)
    val turns = SpeakingTurnState(work)
    val evaluation = SpeakingEvaluationState(work)
    val topics = SpeakingTopicService(work)
    topics.seed()

    // 업무 실행과 API를 조립한다. 작업자의 로그에는 원문 대신 단계·실패 종류·코드만 남긴다.
    val worker = SpeakingEvaluationWorker(
        work, evaluation, results, SpeakingEvaluationExecution(model),
        SpeakingCoachingExecution(model),
    ) { diagnostic ->
        environment.log.warn(
            "Speaking worker failed. stage={} failureClass={} code={}",
            diagnostic.stage, diagnostic.failureClass, diagnostic.code,
        )
    }
    val retention = SpeakingAudioRetention(work, audioStore) { failureClass ->
        environment.log.warn("Speaking audio deletion failed. failureClass={}", failureClass)
    }
    val wake = Channel<Unit>(Channel.CONFLATED)
    routing {
        speakingRoutes(
            context, SpeakingOpeningExecution(work, SpeakingOpeningState(work), conversation, speech, audioStore),
            reads, sessions, turns, SpeakingTurnExecution(work, turns, sessions, conversation, speech, audioStore),
            topics,
            SpeakingAssistanceService(work, SpeakingAssistanceExecution(model)), SpeakingSttReportService(work),
            SpeakingAudioService(work, audioStore), evaluation,
            { user, session -> results.evaluation(user, session) ?: JsonNull },
            reports,
        ) { wake.trySend(Unit) }
    }

    // 저장된 intent가 대기열이며 원본 4개 실행 슬롯과 10초 회수 주기를 유지한다. commit 후 알림은 조회만 앞당긴다.
    val slots = Semaphore(4)
    val active = ConcurrentHashMap.newKeySet<Triple<Long, Long, Int>>()
    launch(CoroutineName("speaking-evaluation-recovery")) {
        while (isActive) {
            try {
                turns.recoverExpired()
                for (pending in worker.pending()) {
                    if (pending in active || !slots.tryAcquire()) continue
                    active += pending
                    launch {
                        try {
                            worker.process(pending.first, pending.second, pending.third)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            environment.log.warn(
                                "Speaking claim/release failed. failureClass={}", failure.javaClass.simpleName,
                            )
                        } finally {
                            active -= pending
                            slots.release()
                        }
                    }
                }
                retention.runOnce(pendingOnly = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                environment.log.warn("Speaking recovery failed. failureClass={}", failure.javaClass.simpleName)
            }
            withTimeoutOrNull(10_000) { wake.receive() }
        }
    }

    // 프로세스 시작 시각과 무관하게 원본 현지 10분 경계에서 세션 만료를 처리한다.
    launch(CoroutineName("speaking-session-expiry")) {
        while (isActive) {
            val now = ZonedDateTime.now()
            val next = now.withSecond(0).withNano(0).plusMinutes((10 - now.minute % 10).toLong())
            delay(Duration.between(now, next).toMillis())
            try {
                while (sessions.expireDue() > 0) yield()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                environment.log.warn("Speaking expiry failed. failureClass={}", failure.javaClass.simpleName)
            }
        }
    }

    // 신규 만료 음성은 원본 현지 시간 04:20에 선택한다. 이미 선택한 삭제만 위 회수 루프에서 재시도한다.
    launch(CoroutineName("speaking-audio-retention")) {
        while (isActive) {
            val now = ZonedDateTime.now()
            val next =
                now.withHour(4).withMinute(20).withSecond(0).withNano(0).let { if (it > now) it else it.plusDays(1) }
            delay(Duration.between(now, next).toMillis())
            try {
                while (retention.runOnce() > 0) yield()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                environment.log.warn("Speaking retention failed. failureClass={}", failure.javaClass.simpleName)
            }
        }
    }
}
