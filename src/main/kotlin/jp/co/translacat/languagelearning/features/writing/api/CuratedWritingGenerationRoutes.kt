package jp.co.translacat.languagelearning.features.writing.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.domain.policy.UserSettingsPolicy
import jp.co.translacat.languagelearning.features.writing.application.CuratedWritingGenerationService
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.shared.http.InternalApiError
import jp.co.translacat.languagelearning.shared.security.INTERNAL_AUTH
import jp.co.translacat.languagelearning.shared.security.InternalUserPrincipal
import kotlinx.serialization.json.*

/** loopback·QA DB 검사를 통과한 bootstrap에서만 설치하는 후보 준비 경계다. */
internal fun Route.curatedWritingGenerationRoutes(service: CuratedWritingGenerationService, settings: SettingsServiceOperations) {
    val root = "/internal/v1/language-learning/writing/curated/preparations"
    suspend fun ApplicationCall.owner(): Long {
        val owner = checkNotNull(principal<InternalUserPrincipal>()).user.userId
        val snapshot = settings.userSnapshot(owner)
        UserSettingsPolicy.requireConfigured(snapshot.result.settings)
        require(snapshot.result.settings.originLanguage == "ko" && snapshot.result.settings.learningLanguage == "ja") {
            "WRITING_LANGUAGE_UNSUPPORTED"
        }
        return owner
    }
    authenticate(INTERNAL_AUTH) {
        post(root) {
            call.generationResult {
                require(call.request.contentType().match(ContentType.Application.Json)) { "WRITING_REQUEST_INVALID" }
                val raw = call.receiveText()
                require(raw.length <= 8192) { "WRITING_REQUEST_INVALID" }
                val body = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: error("WRITING_REQUEST_INVALID")
                val required = setOf("requestKey", "writingType", "targetBand", "topicKey")
                require(body.keys == required || body.keys == required + setOf("repairOfRequestKey", "repairNotes")) {
                    "WRITING_REQUEST_INVALID"
                }
                fun text(key: String) = (body[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: error("WRITING_REQUEST_INVALID")
                val type = runCatching { WritingType.valueOf(text("writingType")) }.getOrNull() ?: error("WRITING_REQUEST_INVALID")
                val band = (body["targetBand"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: error("WRITING_REQUEST_INVALID")
                service.prepare(call.owner(), text("requestKey"), type, band, text("topicKey"),
                    if ("repairOfRequestKey" in body) text("repairOfRequestKey") else null,
                    if ("repairNotes" in body) text("repairNotes") else null)
            }
        }
        get("$root/{requestKey}") {
            call.generationResult { service.get(call.owner(), call.parameters["requestKey"].orEmpty()) }
        }
    }
}

private suspend fun ApplicationCall.generationResult(action: suspend () -> JsonObject) {
    val result = try { action() } catch (failure: Exception) {
        val code = failure.message.orEmpty()
        val status = when (code) {
            "WRITING_REQUEST_INVALID", "WRITING_LANGUAGE_UNSUPPORTED" -> HttpStatusCode.BadRequest
            "WRITING_PREPARATION_NOT_FOUND" -> HttpStatusCode.NotFound
            "WRITING_PREPARATION_CONFLICT", "WRITING_PREPARATION_REPAIR_LIMIT" -> HttpStatusCode.Conflict
            else -> throw failure
        }
        respond(status, InternalApiError(code, "Writing 후보 준비 요청을 처리할 수 없습니다."))
        return
    }
    respond(result)
}
