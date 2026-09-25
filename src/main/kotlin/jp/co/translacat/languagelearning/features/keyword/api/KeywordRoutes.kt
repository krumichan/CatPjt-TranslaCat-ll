package jp.co.translacat.languagelearning.features.keyword.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.keyword.api.dto.*
import jp.co.translacat.languagelearning.features.keyword.application.KeywordOperations
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import jp.co.translacat.languagelearning.shared.security.InternalAuthorizationException
import jp.co.translacat.languagelearning.shared.security.KEYWORD_AUTH
import jp.co.translacat.languagelearning.shared.security.KeywordPrincipal
import java.time.LocalDate
import java.time.format.DateTimeParseException

internal fun Route.keywordRoutes(operations: KeywordOperations) {
    authenticate(KEYWORD_AUTH) {
        route("/internal/v1/language-learning/keywords") {
            get {
                val caller = call.caller()
                call.respond(
                    operations.list(
                        caller.user.userId, caller.hasStartedLearning, call.request.header("X-TranslaCat-Locale"),
                    ).toResponse(),
                )
            }
            post("/custom") {
                val caller = call.caller()
                val request = call.receive<KeywordCreateRequestDto>()
                call.respond(
                    HttpStatusCode.Created,
                    operations.createCustom(caller.user.userId, caller.hasStartedLearning, request.toChange())
                        .toResponse(),
                )
            }
            patch("/custom/{keywordId}") {
                val caller = call.caller()
                val id = call.keywordId()
                val request = call.receive<KeywordUpdateRequestDto>()
                call.respond(
                    operations.updateCustom(
                        caller.user.userId, caller.hasStartedLearning, id, request.toChange(),
                    ).toResponse(),
                )
            }
            delete("/custom/{keywordId}") {
                val caller = call.caller()
                operations.deleteCustom(caller.user.userId, caller.hasStartedLearning, call.keywordId())
                call.respond(true)
            }
            put("/system/{keywordId}/selection") {
                val caller = call.caller()
                val id = call.keywordId()
                val request = call.receive<SystemKeywordSelectionRequestDto>()
                call.respond(
                    operations.selectSystem(
                        caller.user.userId, caller.hasStartedLearning, id, request.selected,
                    ).toResponse(),
                )
            }
            get("/candidates") {
                val caller = call.caller()
                val date = try {
                    LocalDate.parse(call.request.queryParameters["learningDate"] ?: "")
                } catch (_: DateTimeParseException) {
                    throw LearningBusinessException("SETTING_INVALID", "학습 날짜를 확인해 주세요.")
                }
                call.respond(
                    KeywordCandidatesDto(
                        operations.candidates(
                            caller.user.userId, caller.hasStartedLearning, date,
                        ).map { it.toResponse() },
                    ),
                )
            }
        }
        route("/internal/v1/admin/language-learning/system-keywords") {
            get {
                call.administrator()
                call.respond(operations.listSystem().map { it.toResponse() })
            }
            post {
                val caller = call.administrator()
                val request = call.receive<KeywordCreateRequestDto>()
                call.respond(
                    HttpStatusCode.Created,
                    operations.createSystem(caller.user.userId, request.toChange()).toResponse(),
                )
            }
            patch("/{keywordId}") {
                val caller = call.administrator()
                val id = call.keywordId()
                val request = call.receive<KeywordUpdateRequestDto>()
                call.respond(operations.updateSystem(caller.user.userId, id, request.toChange()).toResponse())
            }
        }
    }
}

private fun ApplicationCall.caller() = checkNotNull(principal<KeywordPrincipal>())
private fun ApplicationCall.administrator() =
    caller().also { if (!it.user.administrator) throw InternalAuthorizationException() }

private fun ApplicationCall.keywordId(): Long =
    parameters["keywordId"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw LearningBusinessException(
        "KEYWORD_NOT_FOUND", "Keyword를 찾을 수 없습니다.",
    )
