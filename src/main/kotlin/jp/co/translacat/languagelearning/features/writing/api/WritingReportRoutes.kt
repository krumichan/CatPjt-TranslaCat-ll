package jp.co.translacat.languagelearning.features.writing.api

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.writing.application.WritingReportService
import jp.co.translacat.languagelearning.shared.http.InternalApiError
import jp.co.translacat.languagelearning.shared.security.INTERNAL_AUTH
import jp.co.translacat.languagelearning.shared.security.InternalUserPrincipal
import java.time.LocalDate

internal fun Route.writingReportRoutes(reports: WritingReportService, today: suspend (Long) -> LocalDate) {
    authenticate(INTERNAL_AUTH) {
        get("/internal/v1/language-learning/writing/daily/report") {
            val userId = checkNotNull(call.principal<InternalUserPrincipal>()).user.userId
            val date = today(userId)
            val fromText = call.request.queryParameters["from"]
            val toText = call.request.queryParameters["to"]
            val from =
                if (fromText == null) date.minusDays(29) else runCatching { LocalDate.parse(fromText) }.getOrNull()
            val to = if (toText == null) date else runCatching { LocalDate.parse(toText) }.getOrNull()
            if (from == null || to == null || to.isBefore(from)) {
                call.respond(HttpStatusCode.BadRequest, InternalApiError("WRITING_REQUEST_INVALID", "조회 기간을 확인해 주세요."))
                return@get
            }
            call.respond(reports.report(userId, date, from, to))
        }
    }
}
