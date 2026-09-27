package jp.co.translacat.languagelearning.features.practice.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.practice.application.PracticeAnswerService
import jp.co.translacat.languagelearning.features.practice.application.PracticeContextService
import jp.co.translacat.languagelearning.features.practice.application.PracticeGenerationState
import jp.co.translacat.languagelearning.features.practice.application.PracticeReadService
import jp.co.translacat.languagelearning.features.practice.domain.PracticeDomain
import jp.co.translacat.languagelearning.features.practice.domain.PracticeFailure
import jp.co.translacat.languagelearning.features.practice.domain.PracticePolicy
import jp.co.translacat.languagelearning.shared.http.InternalApiError
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import jp.co.translacat.languagelearning.shared.security.INTERNAL_AUTH
import jp.co.translacat.languagelearning.shared.security.InternalUserPrincipal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.LocalDate

@Serializable
private data class PracticeAnswerRequest(val answer: List<String?>? = null)

internal fun Route.practiceRoutes(
    context: PracticeContextService,
    reads: PracticeReadService,
    answers: PracticeAnswerService,
    generation: PracticeGenerationState,
    learningDate: suspend (Long) -> LocalDate,
) {
    authenticate(INTERNAL_AUTH) {
        route("/internal/v1/language-learning/practice") {
            get("/today") {
                call.practiceResponse {
                    val userId = call.userId()
                    val domain = call.domain()
                    val mode = call.request.queryParameters["mode"] ?: throw PracticeFailure(
                        "LANGUAGE_LEARNING_SETTING_INVALID", 400,
                    )
                    val set = context.today(userId, domain, mode)
                    reads.get(userId, set.id)
                }
            }
            get("/today/status") {
                call.practiceResponse { reads.statuses(call.userId(), learningDate(call.userId()), call.domain()) }
            }
            get("/today/availability") {
                call.practiceResponse {
                    JsonArray(
                        context.availability(call.userId()).map { (mode, available) ->
                            buildJsonObject {
                                put("mode", mode)
                                put("generationAvailable", available)
                                put(
                                    "reason",
                                    if (available) JsonNull else JsonPrimitive(PracticePolicy.B5_STRUCTURE_DEFERRED),
                                )
                            }
                        },
                    )
                }
            }
            get("/sets/{setId}") {
                call.practiceResponse { reads.get(call.userId(), LearningPublicId.decode(call.parameters["setId"])) }
            }
            post("/sets/{setId}/retry-generation") {
                call.practiceResponse {
                    val set = generation.retry(call.userId(), LearningPublicId.decode(call.parameters["setId"]))
                    reads.get(call.userId(), set.id)
                }
            }
            post("/questions/{questionId}/answers") {
                call.practiceResponse {
                    val body = call.receive<PracticeAnswerRequest>()
                    // 기존 Core와 동일하게 소유권 확인 후 비어 있거나 null인 선택지를 답변 정책에서 거부한다.
                    val answer = body.answer.orEmpty().map { it.orEmpty() }
                    reads.answer(
                        answers.submit(call.userId(), LearningPublicId.decode(call.parameters["questionId"]), answer),
                    )
                }
            }
            get("/vocabulary/mastery") {
                call.practiceResponse { reads.mastery(call.userId()) }
            }
            get("/report") {
                call.practiceResponse {
                    val from = call.request.queryParameters["from"]?.let(LocalDate::parse) ?: LocalDate.of(1000, 1, 1)
                    val to = call.request.queryParameters["to"]?.let(LocalDate::parse) ?: LocalDate.of(9999, 12, 31)
                    if (to.isBefore(from)) throw PracticeFailure("LANGUAGE_LEARNING_SETTING_INVALID", 400)
                    reads.report(call.userId(), from, to)
                }
            }
        }
    }
}

private fun ApplicationCall.userId() = checkNotNull(principal<InternalUserPrincipal>()).user.userId
private fun ApplicationCall.domain() = try {
    PracticeDomain.valueOf(request.queryParameters["domain"] ?: "")
} catch (_: IllegalArgumentException) {
    throw PracticeFailure("LANGUAGE_LEARNING_SETTING_INVALID", 400)
}

private suspend fun ApplicationCall.practiceResponse(action: suspend () -> JsonElement) {
    try {
        respond(action())
    } catch (failure: PracticeFailure) {
        respond(
            HttpStatusCode.fromValue(failure.status),
            InternalApiError(
                failure.code,
                if (failure.code == PracticePolicy.VOCABULARY_RETIRED) "독립 Vocabulary 신규 생성은 종료되었습니다. Reading에서 학습을 시작해 주세요."
                else "Reading/Vocabulary 요청 상태를 확인해 주세요.",
            ),
        )
    }
}
