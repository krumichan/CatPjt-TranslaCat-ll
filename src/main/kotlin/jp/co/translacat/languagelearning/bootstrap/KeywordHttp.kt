package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.keyword.api.keywordRoutes
import jp.co.translacat.languagelearning.features.keyword.application.KeywordOperations

/** Authentication은 기존 내부 API 초기화에서 한 번만 설치한다. */
internal suspend fun Application.configureKeywordHttp() {
    if (!loadInternalApiSettings().enabled) return
    val operations = dependencies.resolve<KeywordOperations>()
    routing { keywordRoutes(operations) }
}
