package jp.co.translacat.languagelearning.features.webaiplan.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import jp.co.translacat.languagelearning.features.webaiplan.application.WebAiPlanService
import jp.co.translacat.languagelearning.features.webaiplan.domain.model.*
import jp.co.translacat.languagelearning.shared.security.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

@Serializable private data class PlansResponse(val plans: List<WebAiPlan>)
@Serializable private data class RunsResponse(val runs: List<WebAiRun>)
@Serializable private data class ResultsResponse(val results: List<WebAiResult>)
@Serializable private data class RunRequest(val executionKey: String)
@Serializable private data class ConnectionResponse(val token: String, val planId: String, val plan: WebAiPlan)

internal fun Route.webAiRoutes(service: WebAiPlanService) {
    authenticate(INTERNAL_AUTH) {
        route("/internal/v1/language-learning/web-ai") {
            get("/plans") { call.respond(PlansResponse(service.plans(call.user()))) }
            post("/plans") { call.respond(service.save(call.user(), null, call.body<WebAiPlanChange>())) }
            put("/plans/{id}") { call.respond(service.save(call.user(), call.id(), call.body<WebAiPlanChange>())) }
            delete("/plans/{id}") {
                service.delete(call.user(), call.id())
                call.respond(HttpStatusCode.NoContent)
            }
            post("/plans/{id}/connection") {
                val plan = service.plans(call.user()).singleOrNull { it.id == call.id() }
                    ?: throw WebAiException("WEB_AI_NOT_FOUND", "학습 계획을 찾을 수 없습니다.", 404)
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.respond(ConnectionResponse(service.connection(call.user(), call.id()), call.id(), plan))
            }
            delete("/plans/{id}/connection") {
                service.revoke(call.user(), call.id())
                call.respond(HttpStatusCode.NoContent)
            }
            post("/plans/{id}/runs") {
                call.respond(service.run(call.user(), call.id(), call.body<RunRequest>().executionKey))
            }
            get("/runs") { call.respond(RunsResponse(service.runs(call.user(), call.limit()))) }
            get("/runs/{id}") { call.respond(service.runById(call.user(), call.id())) }
            post("/results") { call.respond(service.submit(call.user(), call.body<WebAiResultSubmission>())) }
            get("/results") { call.respond(ResultsResponse(service.results(call.user(), call.limit()))) }
            get("/results/{id}") { call.respond(service.result(call.user(), call.id())) }
            post("/results/{id}/retry") { call.respond(service.retry(call.user(), call.id())) }
        }
    }
    authenticate(WEB_AI_SERVICE_AUTH) {
        route("/internal/v1/language-learning/web-ai/external") {
            post("/prompt") {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.respond(service.externalRun(call.token(), call.body<RunRequest>().executionKey))
            }
            post("/results") {
                call.respond(service.externalSubmit(call.token(), call.body<WebAiResultSubmission>()))
            }
        }
    }
}

private fun ApplicationCall.user(): Long = checkNotNull(principal<InternalUserPrincipal>()).user.userId
private fun ApplicationCall.id(): String = parameters["id"] ?: throw WebAiException("WEB_AI_INVALID", "식별자가 필요합니다.")
private fun ApplicationCall.limit(): Int = request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20
private fun ApplicationCall.token(): String = request.headers["X-Web-Ai-Token"]
    ?: throw WebAiException("WEB_AI_TOKEN_INVALID", "연동 키가 필요합니다.", 401)

private val webAiJson = Json { ignoreUnknownKeys = false; isLenient = false }

private suspend inline fun <reified T> ApplicationCall.body(): T {
    if (!request.contentType().match(ContentType.Application.Json)) {
        throw WebAiException("WEB_AI_INVALID", "Content-Type: application/json으로 전송해 주세요.", 415)
    }
    // Content-Length를 신뢰하지 않고 chunked 본문도 제한한다. 문항 수 상한과 별개의 전송 보호다.
    val input = receiveChannel()
    val buffer = ByteArray(8192)
    val output = ByteArrayOutputStream()
    while (true) {
        val count = input.readAvailable(buffer)
        if (count == -1) break
        if (output.size() + count > 2 * 1024 * 1024) {
            throw WebAiException("WEB_AI_PAYLOAD_TOO_LARGE", "한 번에 전송할 수 있는 크기를 초과했습니다. 나누어 제출해 주세요.", 413)
        }
        output.write(buffer, 0, count)
    }
    return try {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
        webAiJson.decodeFromString<T>(text)
    } catch (_: Exception) {
        throw WebAiException("WEB_AI_INVALID", "요청 JSON 형식과 필드를 확인해 주세요.")
    }
}
