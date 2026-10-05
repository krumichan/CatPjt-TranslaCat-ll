package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.ExposedGrowthUnitOfWork
import jp.co.translacat.languagelearning.features.leveltest.api.LevelResponseMapper
import jp.co.translacat.languagelearning.features.leveltest.api.dto.LevelTestHistoryDetailResponseDto
import jp.co.translacat.languagelearning.features.leveltest.application.LevelReadService
import jp.co.translacat.languagelearning.features.leveltest.application.LevelSessionService
import jp.co.translacat.languagelearning.features.listening.application.ListeningReadService
import jp.co.translacat.languagelearning.features.listening.infrastructure.persistence.ExposedListeningUnitOfWork
import jp.co.translacat.languagelearning.features.overview.api.overviewRoutes
import jp.co.translacat.languagelearning.features.overview.application.OverviewService
import jp.co.translacat.languagelearning.features.practice.application.PracticeReadService
import jp.co.translacat.languagelearning.features.practice.infrastructure.ExposedPracticeUnitOfWork
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.speaking.application.SpeakingReadService
import jp.co.translacat.languagelearning.features.speaking.application.SpeakingReportService
import jp.co.translacat.languagelearning.features.speaking.application.SpeakingResultApplication
import jp.co.translacat.languagelearning.features.speaking.infrastructure.ExposedSpeakingUnitOfWork
import jp.co.translacat.languagelearning.features.writing.application.WritingReadService
import jp.co.translacat.languagelearning.features.writing.application.WritingReportService
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.ExposedWritingReportQueries
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.ExposedWritingSetUnitOfWork
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement

internal suspend fun Application.configureOverview() {
    if (environment.config.propertyOrNull("overview.enabled")?.getString()?.toBooleanStrictOrNull() != true) return
    check(loadInternalApiSettings().enabled)

    // 조회용 repository만 재사용한다. 기능별 작업자·외부 Provider·오디오 보관 작업은 추가로 시작하지 않는다.
    val runner = dependencies.resolve<JdbcTransactionRunner>()
    val settings = dependencies.resolve<SettingsServiceOperations>()
    val writingQueries = ExposedWritingReportQueries(runner)
    val writingReads = WritingReadService(ExposedWritingSetUnitOfWork(runner))
    val speakingWork = ExposedSpeakingUnitOfWork(runner)
    val speakingResults = SpeakingResultApplication(speakingWork)
    val speaking = SpeakingReportService(
        speakingWork, settings, SpeakingReadService(speakingWork, settings),
        { user, session -> speakingResults.evaluation(user, session) ?: JsonNull }, writingQueries,
    )
    val levelSessions = dependencies.resolve<LevelSessionService>()
    val levelReads = dependencies.resolve<LevelReadService>()

    // 외부 응답 DTO 변환은 조립 경계에 두고 공통 조회 서비스에는 읽기 콜백만 전달한다.
    val service = OverviewService(
        settings, WritingReportService(writingQueries), speaking,
        ListeningReadService(ExposedListeningUnitOfWork(runner), settings),
        PracticeReadService(ExposedPracticeUnitOfWork(runner)),
        ExposedGrowthUnitOfWork(runner), levelSessions,
        { user, session ->
            encodeOverviewLevelHistory(LevelResponseMapper.detail(levelSessions.detail(user, session), levelReads))
        },
    ) { user, set ->
        writingReads.byId(user, set, settings.learningDate(user), settings.adminPolicy().reviewAvailableDays)
            ?: throw LearningBusinessException(
                "LANGUAGE_LEARNING_DAILY_SET_NOT_FOUND", "Daily Writing 학습 세트를 찾을 수 없습니다.",
            )
    }
    routing { overviewRoutes(service) }
}

// JsonElement로 만든 뒤에는 HTTP 직렬화가 빠진 기본값을 복원하지 못한다.
// 일반 레벨 이력과 같은 빈 배열/null 계약을 이 변환 경계에서 유지한다.
private val overviewHistoryJson = Json {
    encodeDefaults = true
    explicitNulls = true
}

internal fun encodeOverviewLevelHistory(detail: LevelTestHistoryDetailResponseDto): JsonElement =
    overviewHistoryJson.encodeToJsonElement(detail)
