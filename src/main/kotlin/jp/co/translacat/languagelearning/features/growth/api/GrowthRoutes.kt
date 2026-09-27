package jp.co.translacat.languagelearning.features.growth.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import jp.co.translacat.languagelearning.features.growth.application.GrowthUnitOfWork
import jp.co.translacat.languagelearning.features.growth.application.QueryGrowth
import jp.co.translacat.languagelearning.features.growth.domain.exception.GrowthConflict
import jp.co.translacat.languagelearning.features.growth.domain.policy.GrowthPolicy
import jp.co.translacat.languagelearning.shared.http.InternalApiError
import jp.co.translacat.languagelearning.shared.security.INTERNAL_AUTH
import jp.co.translacat.languagelearning.shared.security.InternalUserPrincipal
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDate

internal const val GROWTH_MAX_BODY_BYTES = 2_105_344

internal fun Route.growthRoutes(work: GrowthUnitOfWork) {
    val query = QueryGrowth(work)
    authenticate(INTERNAL_AUTH) {
        post("/internal/v1/language-learning/growth/snapshot") {
            call.growthResponse {
                // 사용자 식별자는 인증에서만 얻는다. 이전 수신/preview 필드는 현재 조회 계약에 없다.
                val userId = checkNotNull(call.principal<InternalUserPrincipal>()).user.userId
                val body = call.growthBody()
                require(body.keys.all { it == "masteryKeys" })
                val keys = body["masteryKeys"]?.takeUnless { it is JsonNull }?.jsonArray?.also {
                    require(it.size <= 500)
                }?.map {
                    val value = it.jsonPrimitive
                    require(value.isString && value.content.length <= 200 && value.content.isNotEmpty())
                    value.content
                }
                query.snapshot(userId, keys).toJson()
            }
        }
        get("/internal/v1/language-learning/growth/activities") {
            call.growthResponse {
                val userId = checkNotNull(call.principal<InternalUserPrincipal>()).user.userId
                val p = call.request.queryParameters
                require(p.names().all { it in setOf("source", "from", "to", "afterId") })
                val source = p["source"]?.takeIf { it.isNotEmpty() }?.also { require(it in GrowthPolicy.sources) }
                val from = LocalDate.parse(requireNotNull(p["from"]))
                val to = LocalDate.parse(requireNotNull(p["to"]))
                val after = p["afterId"]?.toLong() ?: 0L
                query.activities(userId, source, from, to, after).toJson()
            }
        }
    }
}

private suspend fun ApplicationCall.growthResponse(block: suspend () -> JsonObject) {
    try {
        respond(block())
    } catch (_: jp.co.translacat.languagelearning.features.learner.domain.exception.LearnerUnavailableException) {
        respond(HttpStatusCode.Forbidden, InternalApiError("LEARNER_UNAVAILABLE", "활성 상태의 학습자가 아닙니다."))
    } catch (failure: GrowthConflict) {
        respond(HttpStatusCode.Conflict, InternalApiError(failure.code, "성장 자료 상태를 확인해 주세요."))
    } catch (_: GrowthBodyTooLarge) {
        respond(HttpStatusCode.PayloadTooLarge, InternalApiError("GROWTH_BODY_TOO_LARGE", "성장 요청이 너무 큽니다."))
    } catch (_: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, InternalApiError("GROWTH_INVALID", "성장 요청 형식을 확인해 주세요."))
    } catch (_: java.time.DateTimeException) {
        respond(HttpStatusCode.BadRequest, InternalApiError("GROWTH_INVALID", "날짜 형식을 확인해 주세요."))
    } catch (_: NoSuchElementException) {
        respond(HttpStatusCode.BadRequest, InternalApiError("GROWTH_INVALID", "필수 필드가 없습니다."))
    } catch (_: java.nio.charset.CharacterCodingException) {
        respond(HttpStatusCode.BadRequest, InternalApiError("GROWTH_INVALID", "UTF-8 요청이 필요합니다."))
    }
}

private class GrowthBodyTooLarge : RuntimeException()

private suspend fun ApplicationCall.growthBody(): JsonObject {
    require(request.contentType().match(ContentType.Application.Json))
    val channel = receiveChannel()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = channel.readAvailable(buffer)
        if (count == -1) break
        if (output.size() + count > GROWTH_MAX_BODY_BYTES) throw GrowthBodyTooLarge()
        output.write(buffer, 0, count)
    }
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
    return Json.parseToJsonElement(text).jsonObject
}
