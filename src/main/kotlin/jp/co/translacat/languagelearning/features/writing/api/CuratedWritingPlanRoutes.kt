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
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.CuratedWritingPlanStore
import jp.co.translacat.languagelearning.shared.http.InternalApiError
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import jp.co.translacat.languagelearning.shared.security.INTERNAL_AUTH
import jp.co.translacat.languagelearning.shared.security.InternalUserPrincipal
import kotlinx.serialization.json.*
import java.time.LocalDate

private const val PLAN_ROOT = "/internal/v1/language-learning/writing/curated/plans"

/** 기능 flag가 켜진 별도 경로만 설치한다. 읽기·재고 보충은 모델 호출을 만들지 않는다. */
internal fun Route.curatedWritingPlanRoutes(
    store: CuratedWritingPlanStore,
    settings: SettingsServiceOperations,
    qaOnly: Boolean,
) {
    suspend fun ApplicationCall.context(type: WritingType = WritingType.TRANSLATION, rePractice: Boolean = false): CuratedWritingStore.Start {
        val owner = checkNotNull(principal<InternalUserPrincipal>()).user.userId
        val snapshot = settings.userSnapshot(owner)
        UserSettingsPolicy.requireConfigured(snapshot.result.settings)
        return CuratedWritingStore.Start(owner, snapshot.learningDate, type,
            checkNotNull(snapshot.result.settings.originLanguage), checkNotNull(snapshot.result.settings.learningLanguage),
            rePractice, qaOnly, settings.adminPolicy().reviewAvailableDays)
    }

    authenticate(INTERNAL_AUTH) {
        get("$PLAN_ROOT/config") { call.respond(store.config(qaOnly)) }
        for (preview in listOf(false, true)) {
            post(if (preview) "$PLAN_ROOT/preview" else PLAN_ROOT) {
                call.planResult {
                    val body = call.planBody()
                    val allowed = setOf("writingType", "targetItemCount", "rePractice") +
                        if (preview) emptySet() else setOf("allowPartial")
                    require(body.keys.all { it in allowed } && body.keys.containsAll(setOf("writingType", "targetItemCount"))) {
                        "WRITING_REQUEST_INVALID"
                    }
                    val type = runCatching { WritingType.valueOf(body.string("writingType")) }.getOrNull()
                        ?: error("WRITING_REQUEST_INVALID")
                    val target = body.integer("targetItemCount")
                    store.limits.requireTarget(target)
                    val request = call.context(type, body.boolean("rePractice"))
                    if (preview) store.preview(request, target)
                    else store.start(request, target, body.boolean("allowPartial"))
                }
            }
        }
        get("$PLAN_ROOT/{setId}") {
            call.planResult {
                val request = call.context()
                store.byId(request.userId, call.publicId("setId"), request.date, request.reviewDays)
                    ?: error("WRITING_SET_NOT_FOUND")
            }
        }
        get("$PLAN_ROOT/history/{date}") {
            call.planResult {
                val date = runCatching { LocalDate.parse(call.parameters["date"]) }.getOrNull()
                    ?: error("WRITING_REQUEST_INVALID")
                val type = runCatching { WritingType.valueOf(call.request.queryParameters["writingType"].orEmpty()) }.getOrNull()
                    ?: error("WRITING_REQUEST_INVALID")
                val request = call.context(type)
                store.byDate(request.userId, date, type, request.date, request.reviewDays)
                    ?: error("WRITING_SET_NOT_FOUND")
            }
        }
        post("$PLAN_ROOT/{setId}/target") {
            call.planResult {
                val body = call.planBody()
                require(body.keys == setOf("targetItemCount", "planRevision")) { "WRITING_REQUEST_INVALID" }
                val target = body.integer("targetItemCount")
                store.limits.requireTarget(target)
                val request = call.context()
                store.expand(request, call.publicId("setId"), target, body.integer("planRevision"))
            }
        }
        post("$PLAN_ROOT/{setId}/restore") {
            call.planResult {
                val body = call.planBody()
                require(body.keys == setOf("planRevision")) { "WRITING_REQUEST_INVALID" }
                store.restore(call.context(), call.publicId("setId"), body.integer("planRevision"))
            }
        }
        post("$PLAN_ROOT/items/{itemId}/answers") {
            call.planResult {
                val body = call.planBody()
                require(body.keys == setOf("answer", "contentRevision")) { "WRITING_REQUEST_INVALID" }
                store.submit(call.context(), call.publicId("itemId"), body.string("answer"), body.string("contentRevision"))
            }
        }
        post("$PLAN_ROOT/answers/{answerId}/feedback/retry") {
            call.planResult {
                require(call.planBody().isEmpty()) { "WRITING_REQUEST_INVALID" }
                store.retryFeedback(call.context(), call.publicId("answerId"))
            }
        }
    }
}

private fun ApplicationCall.publicId(key: String): Long =
    runCatching { LearningPublicId.decode(parameters[key]) }.getOrElse { error("WRITING_REQUEST_INVALID") }

private suspend fun ApplicationCall.planBody(): JsonObject {
    require(request.contentType().match(ContentType.Application.Json)) { "WRITING_REQUEST_INVALID" }
    val raw = receiveText()
    require(raw.length <= 16_384) { "WRITING_REQUEST_INVALID" }
    return runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrElse { error("WRITING_REQUEST_INVALID") }
}
private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("WRITING_REQUEST_INVALID")
private fun JsonObject.integer(key: String): Int =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 }
        ?: error("WRITING_REQUEST_INVALID")
private fun JsonObject.boolean(key: String): Boolean = if (key !in this) false else
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("WRITING_REQUEST_INVALID")

private suspend fun ApplicationCall.planResult(action: suspend () -> JsonObject) {
    val result = try { action() } catch (failure: Exception) {
        val code = failure.message.orEmpty()
        val status = when (code) {
            "WRITING_REQUEST_INVALID", "WRITING_TARGET_INVALID" -> HttpStatusCode.BadRequest
            "WRITING_ITEM_NOT_FOUND", "WRITING_SET_NOT_FOUND", "WRITING_ANSWER_NOT_FOUND" -> HttpStatusCode.NotFound
            "WRITING_CONTENT_NOT_AVAILABLE", "WRITING_POLICY_CONFLICT", "WRITING_LANGUAGE_CONFLICT",
            "WRITING_RELEASE_CONFLICT", "WRITING_TARGET_CONFLICT", "WRITING_TARGET_SHRINK_NOT_ALLOWED",
            "WRITING_PLAN_STALE", "WRITING_SUPPLY_BUSY", "WRITING_SUPPLY_STALE", "WRITING_REVIEW_EXPIRED",
            "WRITING_ITEM_STALE", "WRITING_ANSWER_REQUIRED", "WRITING_ANSWER_NOT_ALLOWED", "LEVEL_TEST_REQUIRED",
            "WRITING_FEEDBACK_DISABLED", "WRITING_FEEDBACK_STALE", "WRITING_FEEDBACK_BUSY",
            "WRITING_FEEDBACK_UNCERTAIN", "WRITING_FEEDBACK_RETRY_LIMIT" -> HttpStatusCode.Conflict
            else -> throw failure
        }
        respond(status, InternalApiError(code, "Writing 계획 요청을 처리할 수 없습니다."))
        return
    }
    respond(result)
}
