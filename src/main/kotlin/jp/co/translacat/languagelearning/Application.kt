package jp.co.translacat.languagelearning

import io.ktor.server.application.*
import jp.co.translacat.languagelearning.bootstrap.*

suspend fun Application.module() {
    configureHttp()
    configureMonitoring()
    configureSerialization()

    configureStatusPages()
    configureRequestValidation()

    configureDependencyInjection()
    configurePersistence()

    configureRouting()
    configureSettingsHttp()
    configureKeywordHttp()
    configureResultJournal()
    configureGrowth()
    configureLevelTest()
}
