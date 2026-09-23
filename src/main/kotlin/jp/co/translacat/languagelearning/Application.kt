package jp.co.translacat.languagelearning

import io.ktor.server.application.Application
import jp.co.translacat.languagelearning.bootstrap.configureDependencyInjection
import jp.co.translacat.languagelearning.bootstrap.configureHttp
import jp.co.translacat.languagelearning.bootstrap.configureMonitoring
import jp.co.translacat.languagelearning.bootstrap.configurePersistence
import jp.co.translacat.languagelearning.bootstrap.configureRequestValidation
import jp.co.translacat.languagelearning.bootstrap.configureRouting
import jp.co.translacat.languagelearning.bootstrap.configureSerialization
import jp.co.translacat.languagelearning.bootstrap.configureStatusPages

suspend fun Application.module() {
    configureHttp()
    configureMonitoring()
    configureSerialization()

    configureStatusPages()
    configureRequestValidation()

    configureDependencyInjection()
    configurePersistence()

    configureRouting()
}