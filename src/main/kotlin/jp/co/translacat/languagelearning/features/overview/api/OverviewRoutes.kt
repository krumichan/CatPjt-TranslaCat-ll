package jp.co.translacat.languagelearning.features.overview.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskType
import jp.co.translacat.languagelearning.features.overview.application.OverviewService
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingFailure
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import jp.co.translacat.languagelearning.shared.http.InternalApiError
import jp.co.translacat.languagelearning.shared.security.INTERNAL_AUTH
import jp.co.translacat.languagelearning.shared.security.InternalUserPrincipal
import kotlinx.serialization.json.JsonElement
import java.time.LocalDate

internal fun Route.overviewRoutes(service: OverviewService) {
    learningEvidenceRoutes(service.evidenceQuery)
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
            overviewHistoryDetailRoute(service::detail)
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

internal fun Route.overviewHistoryDetailRoute(read: suspend (Long, String) -> JsonElement) {
    get("/history/{activityId}") {
        // 기능의 소유권·삭제 판정은 그대로 두고 Speaking 직접 조회와 같은 오류 계약을 전달한다.
        try {
            call.respond(read(call.user(), checkNotNull(call.parameters["activityId"])))
        } catch (failure: SpeakingFailure) {
            call.respond(
                HttpStatusCode.fromValue(failure.status),
                InternalApiError(failure.code, "Speaking 요청 상태를 확인해 주세요."),
            )
        }
        // 저장소 장애 등 다른 실패는 공통 오류 처리에 남겨 빈 상세나 정상 응답으로 바꾸지 않는다.
    }
}

private fun ApplicationCall.user() = checkNotNull(principal<InternalUserPrincipal>()).user.userId
private fun ApplicationCall.query(name: String) = request.queryParameters[name]
private fun ApplicationCall.date(name: String): LocalDate? =
    query(name)?.let { runCatching { LocalDate.parse(it) }.getOrElse { invalid() } }

private fun ApplicationCall.task(): ListeningTaskType? =
    query("taskType")?.let { runCatching { ListeningTaskType.valueOf(it) }.getOrElse { invalid() } }

private fun invalid(): Nothing = throw LearningBusinessException("INVALID_REQUEST", "조회 조건을 확인해 주세요.")
