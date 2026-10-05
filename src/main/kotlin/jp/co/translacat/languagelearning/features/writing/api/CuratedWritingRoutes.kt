package jp.co.translacat.languagelearning.features.writing.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.domain.policy.UserSettingsPolicy
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.CuratedWritingStore
import jp.co.translacat.languagelearning.shared.http.InternalApiError
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import jp.co.translacat.languagelearning.shared.security.INTERNAL_AUTH
import jp.co.translacat.languagelearning.shared.security.InternalUserPrincipal
import kotlinx.serialization.json.*
import java.time.LocalDate

private const val ROOT = "/internal/v1/language-learning/writing/curated"

/** 신규 정책은 별도 경로이며 구형 점수형 route와 암묵적으로 교차하지 않는다. */
internal fun Route.curatedWritingRoutes(
    store: CuratedWritingStore,
    settings: SettingsServiceOperations,
    qaOnly: Boolean,
) {
    authenticate(INTERNAL_AUTH) {
        post("$ROOT/sets") {
            val owner = checkNotNull(call.principal<InternalUserPrincipal>()).user.userId
            val body = call.curatedBody() ?: return@post
            if (body.keys !in setOf(setOf("writingType"), setOf("writingType", "rePractice")) ||
                ("rePractice" in body && body["rePractice"].booleanOrNullSafe() == null)) {
                call.curatedError("WRITING_REQUEST_INVALID", HttpStatusCode.BadRequest)
                return@post
            }
            val type = parseType(body["writingType"]) ?: run {
                call.curatedError("WRITING_REQUEST_INVALID", HttpStatusCode.BadRequest)
                return@post
            }
            val rePractice = body["rePractice"].booleanOrNullSafe() ?: false
            val snapshot = settings.userSnapshot(owner)
            UserSettingsPolicy.requireConfigured(snapshot.result.settings)
            val user = snapshot.result.settings
            val reviewDays = settings.adminPolicy().reviewAvailableDays
            val result = runCatching {
                store.start(CuratedWritingStore.Start(
                    owner, snapshot.learningDate, type,
                    checkNotNull(user.originLanguage), checkNotNull(user.learningLanguage),
                    rePractice, qaOnly, reviewDays,
                ))
            }.getOrElse { failure ->
                if (call.curatedFailure(failure)) return@post
                throw failure
            }
            call.respond(result)
        }

        get("$ROOT/sets/{setId}") {
            val owner = checkNotNull(call.principal<InternalUserPrincipal>()).user.userId
            val setId = LearningPublicId.decode(call.parameters["setId"])
            val today = settings.learningDate(owner)
            val result = store.byId(owner, setId, today, settings.adminPolicy().reviewAvailableDays)
            if (result == null) call.curatedError("WRITING_SET_NOT_FOUND", HttpStatusCode.NotFound)
            else call.respond(result)
        }

        get("$ROOT/history/{date}") {
            val owner = checkNotNull(call.principal<InternalUserPrincipal>()).user.userId
            val date = runCatching { LocalDate.parse(call.parameters["date"]) }.getOrNull()
            val type = parseType(call.request.queryParameters["writingType"]?.let(::JsonPrimitive))
            if (date == null || type == null) {
                call.curatedError("WRITING_REQUEST_INVALID", HttpStatusCode.BadRequest)
                return@get
            }
            val result = store.byDate(owner, date, type, settings.learningDate(owner),
                settings.adminPolicy().reviewAvailableDays)
            if (result == null) call.curatedError("WRITING_SET_NOT_FOUND", HttpStatusCode.NotFound)
            else call.respond(result)
        }

        post("$ROOT/items/{itemId}/answers") {
            val owner = checkNotNull(call.principal<InternalUserPrincipal>()).user.userId
            val itemId = LearningPublicId.decode(call.parameters["itemId"])
            val body = call.curatedBody() ?: return@post
            val answer = body["answer"].stringOrNullSafe()
            val revision = body["contentRevision"].stringOrNullSafe()
            if (answer == null || revision == null || body.keys != setOf("answer", "contentRevision")) {
                call.curatedError("WRITING_REQUEST_INVALID", HttpStatusCode.BadRequest)
                return@post
            }
            val snapshot = settings.userSnapshot(owner)
            val result = runCatching {
                store.submit(owner, itemId, answer, revision, snapshot.learningDate,
                    settings.adminPolicy().reviewAvailableDays)
            }.getOrElse { failure ->
                if (call.curatedFailure(failure)) return@post
                throw failure
            }
            call.respond(result)
        }

        post("$ROOT/sets/{setId}/replace") {
            val owner = checkNotNull(call.principal<InternalUserPrincipal>()).user.userId
            val setId = LearningPublicId.decode(call.parameters["setId"])
            val body = call.curatedBody() ?: return@post
            if (body.keys != setOf("rePractice")) {
                call.curatedError("WRITING_REQUEST_INVALID", HttpStatusCode.BadRequest)
                return@post
            }
            val rePractice = body["rePractice"].booleanOrNullSafe()
            if (rePractice == null) {
                call.curatedError("WRITING_REQUEST_INVALID", HttpStatusCode.BadRequest)
                return@post
            }
            val result = runCatching {
                store.replace(owner, setId, settings.learningDate(owner),
                    settings.adminPolicy().reviewAvailableDays, rePractice, qaOnly)
            }.getOrElse { failure ->
                if (call.curatedFailure(failure)) return@post
                throw failure
            }
            call.respond(result)
        }
    }
}

private fun parseType(raw: JsonElement?): WritingType? = runCatching {
    WritingType.valueOf(raw?.jsonPrimitive?.content ?: return null)
}.getOrNull()

private fun JsonElement?.booleanOrNullSafe(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
private fun JsonElement?.stringOrNullSafe(): String? = (this as? JsonPrimitive)
    ?.takeIf { it.isString }?.content

private suspend fun ApplicationCall.curatedBody(): JsonObject? {
    val raw = runCatching { receiveText() }.getOrNull()
    if (raw == null || raw.length > 16_384 || !request.contentType().match(ContentType.Application.Json)) {
        curatedError("WRITING_REQUEST_INVALID", HttpStatusCode.BadRequest)
        return null
    }
    val body = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
    if (body == null) curatedError("WRITING_REQUEST_INVALID", HttpStatusCode.BadRequest)
    return body
}

private suspend fun ApplicationCall.curatedFailure(failure: Throwable): Boolean {
    val code = failure.message ?: return false
    val known = setOf(
        "WRITING_CONTENT_NOT_AVAILABLE", "WRITING_REPRACTICE_REQUIRED", "WRITING_POLICY_CONFLICT",
        "WRITING_ITEM_NOT_FOUND", "WRITING_SET_NOT_FOUND", "WRITING_ITEM_STALE",
        "WRITING_REVIEW_EXPIRED", "WRITING_ANSWER_REQUIRED", "WRITING_ANSWER_NOT_ALLOWED",
        "WRITING_REGENERATION_LIMIT", "WRITING_NO_UNANSWERED_ITEM", "WRITING_REGENERATION_CONFLICT",
        "LEVEL_TEST_REQUIRED",
    )
    if (code !in known) return false
    curatedError(code, if (code == "WRITING_ITEM_NOT_FOUND" || code == "WRITING_SET_NOT_FOUND")
        HttpStatusCode.NotFound else HttpStatusCode.Conflict)
    return true
}

private suspend fun ApplicationCall.curatedError(code: String, status: HttpStatusCode) {
    respond(status, InternalApiError(code, when (code) {
        "WRITING_CONTENT_NOT_AVAILABLE" -> "현재 조건에서 제공할 검수 문항이 부족합니다."
        "WRITING_REPRACTICE_REQUIRED" -> "새 문항이 부족합니다. 기존 문항 재학습을 선택할 수 있습니다."
        "WRITING_ITEM_STALE" -> "문항이 변경되었습니다. 최신 내용을 확인해 주세요."
        else -> "Writing 요청을 처리할 수 없습니다."
    }))
}
