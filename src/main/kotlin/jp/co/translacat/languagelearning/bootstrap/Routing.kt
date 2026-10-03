package jp.co.translacat.languagelearning.bootstrap

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.di.*
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Application.configureRouting() {
    routing {
        get("/health/ready") {
            val settings = dependencies.resolve<DatabaseSettings>()
            val ready = if (settings.enabled) {
                val database = dependencies.resolve<DatabaseFactory>()
                withContext(Dispatchers.IO) { database.isReady() }
            } else false
            call.respond(
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                mapOf("status" to if (ready) "READY" else "NOT_READY"),
            )
        }
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
