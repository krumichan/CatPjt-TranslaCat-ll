package jp.co.translacat.languagelearning.features.overview.api

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskType
import jp.co.translacat.languagelearning.features.overview.application.OverviewService
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import jp.co.translacat.languagelearning.shared.security.INTERNAL_AUTH
import jp.co.translacat.languagelearning.shared.security.InternalUserPrincipal
import java.time.LocalDate

internal fun Route.overviewRoutes(service: OverviewService) {
    authenticate(INTERNAL_AUTH) {
        route("/internal/v1/language-learning/overview") {
            get("/dashboard") {
                call.respond(
                    service.dashboard(
                        call.user(), call.date("from"), call.date("to"), call.query("source"), call.task(),
                    ),
                )
            }
            get("/history") {
                call.respond(
                    service.history(
                        call.user(), call.query("source"), call.query("period"), call.query("status"), call.task(),
                    ),
                )
            }
            get("/history/{activityId}") {
                call.respond(
                    service.detail(call.user(), checkNotNull(call.parameters["activityId"])),
                )
            }
            get("/streak") { call.respond(service.streak(call.user())) }
            get("/insights") {
                call.respond(
                    service.insights(call.user(), call.query("source"), call.query("limit")?.toIntOrNull() ?: 30),
                )
            }
            get("/trend") {
                call.respond(
                    service.trend(
                        call.user(), call.query("source"), call.date("from") ?: invalid(), call.date("to") ?: invalid(),
                    ),
                )
            }
        }
    }
}

private fun ApplicationCall.user() = checkNotNull(principal<InternalUserPrincipal>()).user.userId
private fun ApplicationCall.query(name: String) = request.queryParameters[name]
private fun ApplicationCall.date(name: String): LocalDate? =
    query(name)?.let { runCatching { LocalDate.parse(it) }.getOrElse { invalid() } }

private fun ApplicationCall.task(): ListeningTaskType? =
    query("taskType")?.let { runCatching { ListeningTaskType.valueOf(it) }.getOrElse { invalid() } }

private fun invalid(): Nothing = throw LearningBusinessException("INVALID_REQUEST", "조회 조건을 확인해 주세요.")
