package jp.co.translacat.languagelearning.bootstrap

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.configureRouting() {
    routing {
        get("/health") {
            call.respond(
                status = HttpStatusCode.OK,
                message = mapOf(
                    "status" to "UP",
                    "service" to "translacat-language-learning",
                ),
            )
        }
    }
}