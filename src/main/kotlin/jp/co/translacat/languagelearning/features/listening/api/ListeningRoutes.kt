package jp.co.translacat.languagelearning.features.listening.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.listening.application.ListeningContextService
import jp.co.translacat.languagelearning.features.listening.application.ListeningGenerationService
import jp.co.translacat.languagelearning.features.listening.application.ListeningReadService
import jp.co.translacat.languagelearning.features.listening.application.ListeningSessionService
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningAssistanceUsage
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningAudioState
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskType
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import jp.co.translacat.languagelearning.shared.security.INTERNAL_AUTH
import jp.co.translacat.languagelearning.shared.security.InternalUserPrincipal
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.util.*

internal fun Route.listeningRoutes(
    context: ListeningContextService, reads: ListeningReadService,
    sessions: ListeningSessionService, generation: ListeningGenerationService,
) {
    authenticate(INTERNAL_AUTH) {
        route("/internal/v1/language-learning/listening") {
            get("/dashboard") {
                call.respond(reads.dashboard(call.user(), call.date("from"), call.date("to"), call.queryTask()))
            }
            get("/metric-trends") {
                call.respond(
                    reads.metricTrends(
                        call.user(), call.request.queryParameters["learningLanguage"] ?: invalid(),
                        call.date("from") ?: invalid(), call.date("to") ?: invalid(), call.queryTask(),
                    ),
                )
            }
            get("/report") {
                call.respond(
                    reads.report(
                        call.user(), call.date("from") ?: invalid(), call.date("to") ?: invalid(), call.queryTask(),
                    ),
                )
            }
            get("/sessions/{sessionId}/history") { call.respond(reads.history(call.user(), call.id("sessionId"))) }
            post("/recommendations/{recommendationId}/dismiss") {
                reads.dismiss(call.user(), call.id("recommendationId"))
                call.respond(JsonNull)
            }
            get("/today/status") { call.respond(reads.todayStatuses(call.user())) }
            get("/today") {
                val user = call.user()
                val set = context.getOrCreate(user)
                call.respond(reads.set(user, set.id))
            }
            post("/daily-sets") {
                val body = call.body()
                val user = call.user()
                val set = context.getOrCreate(
                    user, body.text("learningMode") ?: "DICTATION",
                    body.text("difficulty") ?: "MY_LEVEL", body["itemCount"]?.jsonPrimitive?.intOrNull,
                )
                call.respond(reads.set(user, set.id))
            }
            get("/daily-sets/{setId}") { call.respond(reads.set(call.user(), call.id("setId"))) }
            post("/daily-sets/{setId}/retry-generation") {
                val set = generation.retryGeneration(call.user(), call.id("setId"))
                call.respond(reads.set(call.user(), set.id))
            }
            post("/items/{itemId}/retry-tts") {
                val set = generation.retryTts(call.user(), call.id("itemId"))
                call.respond(reads.set(call.user(), set.id))
            }
            get("/items/{itemId}/audio") { call.audio(reads.referenceAudio(call.user(), call.id("itemId"))) }
            get("/policy") { call.respond(reads.policy()) }
            post("/sessions") {
                val body = call.body()
                val session = sessions.create(
                    call.user(), LearningPublicId.decode(body.text("dailySetId")), body.tasks(),
                    body.text("idempotencyKey"),
                )
                call.respond(reads.session(call.user(), session.id))
            }
            get("/sessions/active") {
                val value = sessions.active(call.user())
                val view = value?.let { reads.session(call.user(), it.id) }
                call.respond(buildJsonObject { put("active", value != null); put("session", view ?: JsonNull) })
            }
            get("/sessions/{sessionId}") {
                sessions.get(call.user(), call.id("sessionId"))
                call.respond(reads.session(call.user(), call.id("sessionId")))
            }
            post("/sessions/{sessionId}/resume") {
                val value = sessions.resume(call.user(), call.id("sessionId"))
                if (value.status == "ABANDONED") throw LearningBusinessException(
                    "LISTENING_SESSION_EXPIRED", "Listening Session 재개 시간이 만료되었습니다.",
                )
                call.respond(reads.session(call.user(), value.id))
            }
            get("/sessions/{sessionId}/items/{itemId}") {
                call.respond(
                    reads.item(call.user(), call.id("sessionId"), call.id("itemId")),
                )
            }
            post("/attempts/{attemptId}/responses/{taskType}") {
                val body = call.body()
                val attempt = call.id("attemptId")
                val task = call.task()
                sessions.answer(
                    call.user(), reads.sessionForAttempt(call.user(), attempt), attempt, task,
                    body.text("answer") ?: "", body.usage(),
                )
                call.respond(reads.task(call.user(), attempt, task))
            }
            post("/attempts/{attemptId}/responses/{taskType}/assistance") {
                val usage = call.receive<JsonArray>().map { value -> value.jsonObject.usageValue() }
                val attempt = call.id("attemptId")
                val task = call.task()
                sessions.taskAssistance(
                    call.user(), reads.sessionForAttempt(call.user(), attempt), attempt, task, usage,
                )
                call.respond(reads.task(call.user(), attempt, task))
            }
            post("/attempts/{attemptId}/assistance/{assistanceType}") {
                val attempt = call.id("attemptId")
                sessions.assistance(
                    call.user(), reads.sessionForAttempt(call.user(), attempt), attempt,
                    call.parameters["assistanceType"] ?: "",
                )
                call.respond(reads.attempt(call.user(), attempt))
            }
            post("/attempts/{attemptId}/audio-upload") {
                val body = call.body()
                val encoded = body.text("audioBase64") ?: invalid()
                // 외부 multipart 파일은 BE에서 바이트 그대로 전달한다. LL에서 크기·형식·소유권을 다시 검사한다.
                if (encoded.length > 28_000_000) invalid()
                val bytes = try {
                    Base64.getDecoder().decode(encoded)
                } catch (_: IllegalArgumentException) {
                    invalid()
                }
                val attempt = call.id("attemptId")
                sessions.upload(
                    call.user(), reads.sessionForAttempt(call.user(), attempt), attempt, bytes,
                    body.text("contentType") ?: "application/octet-stream",
                    body["durationMs"]?.jsonPrimitive?.intOrNull ?: invalid(),
                )
                call.respond(reads.uploadView(call.user(), attempt))
            }
            post("/attempts/{attemptId}/submit") {
                val body = call.body()
                val attempt = call.id("attemptId")
                sessions.submit(
                    call.user(), reads.sessionForAttempt(call.user(), attempt), attempt,
                    body["actualDurationMs"]?.jsonPrimitive?.longOrNull ?: 0,
                )
                call.respond(reads.attempt(call.user(), attempt))
            }
            post("/attempts/{attemptId}/retry-evaluation") {
                val task = call.body().text("taskType")?.let(::task) ?: invalid()
                val attempt = call.id("attemptId")
                sessions.retryEvaluation(call.user(), reads.sessionForAttempt(call.user(), attempt), attempt, task)
                call.respond(reads.attempt(call.user(), attempt))
            }
            post("/sessions/{sessionId}/retry-failed-evaluations") {
                val result = sessions.retryFailed(call.user(), call.id("sessionId"))
                call.respond(
                    buildJsonObject {
                        put("failedTaskCount", result.first); put("retriedTaskCount", result.second); put(
                        "exhaustedTaskCount", result.third,
                    )
                    },
                )
            }
            post("/attempts/{attemptId}/answer") {
                val attempt = call.id("attemptId")
                sessions.reveal(call.user(), reads.sessionForAttempt(call.user(), attempt), attempt)
                call.respond(reads.revealView(call.user(), attempt))
            }
            post("/sessions/{sessionId}/items/{itemId}/practice-attempts") {
                val body = call.body()
                val value = sessions.practice(
                    call.user(), call.id("sessionId"), call.id("itemId"), body.tasks(), body.text("idempotencyKey"),
                )
                call.respond(
                    reads.attempt(
                        call.user(),
                        value.attempts.filter { it.itemId == call.id("itemId") && it.purpose == "PRACTICE" }.last().id,
                    ),
                )
            }
            post("/attempts/{attemptId}/skip") {
                val attempt = call.id("attemptId")
                sessions.skip(call.user(), reads.sessionForAttempt(call.user(), attempt), attempt)
                call.respond(reads.attempt(call.user(), attempt))
            }
            get("/responses/{responseId}/audio") { call.audio(reads.responseAudio(call.user(), call.id("responseId"))) }
            post("/responses/{responseId}/reports") {
                val body = call.body()
                val response = call.id("responseId")
                val value = sessions.report(
                    call.user(), reads.sessionForResponse(call.user(), response), response,
                    body.text("reasonCode") ?: "", body.text("comment"),
                    body["consentToRetainAudio"]?.jsonPrimitive?.booleanOrNull ?: false,
                    body.text("idempotencyKey") ?: "",
                )
                call.respond(reads.reportView(call.user(), response, value))
            }
            post("/sessions/{sessionId}/items/{itemId}/playbacks") {
                val body = call.body()
                sessions.playback(
                    call.user(), call.id("sessionId"), call.id("itemId"),
                    LearningPublicId.decode(body.text("attemptId")),
                    body.text("playbackType") ?: "", body.text("clientEventId") ?: "",
                )
                call.respond(JsonNull)
            }
            post("/sessions/{sessionId}/complete") {
                val value = sessions.complete(call.user(), call.id("sessionId"))
                if (value.status == "ABANDONED") throw LearningBusinessException(
                    "LISTENING_SESSION_EXPIRED", "Listening Session이 만료되었습니다.",
                )
                call.respond(reads.result(call.user(), value.id))
            }
            get("/sessions/{sessionId}/result") { call.respond(reads.result(call.user(), call.id("sessionId"))) }
        }
    }
}

private fun ApplicationCall.user() = checkNotNull(principal<InternalUserPrincipal>()).user.userId
private fun ApplicationCall.id(name: String) = LearningPublicId.decode(parameters[name])
private fun ApplicationCall.task() = task(parameters["taskType"] ?: "")
private fun ApplicationCall.queryTask() = request.queryParameters["taskType"]?.let(::task)
private fun ApplicationCall.date(name: String) = request.queryParameters[name]?.let(LocalDate::parse)
private fun task(value: String) = try {
    ListeningTaskType.valueOf(value)
} catch (_: IllegalArgumentException) {
    invalid()
}

private suspend fun ApplicationCall.body(): JsonObject =
    receiveText().takeIf(String::isNotBlank)?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.tasks() = (this["selectedTaskTypes"] as? JsonArray)?.map { task(it.jsonPrimitive.content) }
private fun JsonObject.usage() = (this["assistanceUsage"] as? JsonArray).orEmpty().map { it.jsonObject.usageValue() }
private fun JsonObject.usageValue() =
    ListeningAssistanceUsage(text("type") ?: "", this["count"]?.jsonPrimitive?.intOrNull ?: 0)

private suspend fun ApplicationCall.audio(value: ListeningAudioState) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respondBytes(checkNotNull(value.bytes), ContentType.parse(value.contentType))
}

private fun invalid(): Nothing =
    throw LearningBusinessException("LISTENING_INVALID_STATE", "Listening 요청 상태가 올바르지 않습니다.")
