package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.routing.*

fun Application.configureHttp() {
    routing {
        // 문서는 공개되지만 실제 Settings 경로는 internalApi와 JWT 검증으로 별도 보호된다.
        swaggerUI(path = "openapi", swaggerFile = "documentation.yaml")
    }
}
