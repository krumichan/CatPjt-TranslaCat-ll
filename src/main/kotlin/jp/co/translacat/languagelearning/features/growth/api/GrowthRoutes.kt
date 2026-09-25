package jp.co.translacat.languagelearning.features.growth.api

import jp.co.translacat.languagelearning.features.growth.application.*
import jp.co.translacat.languagelearning.features.growth.domain.exception.*
import jp.co.translacat.languagelearning.features.growth.domain.policy.GrowthPolicy
import jp.co.translacat.languagelearning.shared.http.InternalApiError
import jp.co.translacat.languagelearning.shared.security.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.sql.SQLException
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

internal fun Route.growthRoutes(work: GrowthUnitOfWork, sourceId: String, clock: Clock = Clock.systemUTC()) {
    val accept = AcceptGrowthBatch(work, sourceId)
    val query = QueryGrowth(work, sourceId, clock)
    authenticate(GROWTH_AUTH) {
        post("/internal/v1/service/language-learning/growth/commands") {
            call.growthResponse { accept.execute(GrowthWire.event(call.growthBody())).toJson() }
        }
    }
    authenticate(INTERNAL_AUTH) {
        post("/internal/v1/language-learning/growth/snapshot") {
            call.growthResponse {
                val userId = checkNotNull(call.principal<InternalUserPrincipal>()).user.userId
                val f = GrowthWire.Fields(call.growthBody(), setOf("sourceInstanceId", "minimumSequence", "previewOperations", "masteryKeys"))
                if (f.text("sourceInstanceId", 36) != sourceId) throw GrowthConflict("GROWTH_SOURCE_MISMATCH")
                val minimum = f.long("minimumSequence").also { require(it >= 0) }
                val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                val changes = f.array("previewOperations", GrowthWire.MAX_OPERATIONS).map { GrowthWire.operation(it.jsonObject, userId, now) }
                require(changes.map { it.key }.distinct().size == changes.size)
                val keys = f.optionalArray("masteryKeys", 500)?.let { GrowthWire.strings(it, 500, 200) }
                query.snapshot(userId, sourceId, minimum, changes, keys).toJson()
            }
        }
        get("/internal/v1/language-learning/growth/activities") {
            call.growthResponse {
                val userId = checkNotNull(call.principal<InternalUserPrincipal>()).user.userId
                val p = call.request.queryParameters
                if (p["sourceInstanceId"] != sourceId) throw GrowthConflict("GROWTH_SOURCE_MISMATCH")
                val minimum = requireNotNull(p["minimumSequence"]).toLong().also { require(it >= 0) }
                val source = p["source"]?.takeIf { it.isNotEmpty() }?.also { require(it in GrowthPolicy.sources) }
                val from = LocalDate.parse(requireNotNull(p["from"]))
                val to = LocalDate.parse(requireNotNull(p["to"]))
                require(!to.isBefore(from))
                val after = p["afterId"]?.toLong() ?: 0L
                require(after >= 0)
                query.activities(userId, sourceId, minimum, source, from, to, after).toJson()
            }
        }
    }
}

private suspend fun ApplicationCall.growthResponse(block: suspend () -> JsonObject) {
    try { respond(block()) }
    catch (failure: GrowthPending) {
        response.headers.append(HttpHeaders.RetryAfter, "1")
        respond(HttpStatusCode.ServiceUnavailable, buildJsonObject {
            put("code", "GROWTH_SYNC_PENDING"); put("message", "성장 결과 반영 중입니다."); put("retryable", true)
            put("requiredSequence", failure.required); put("appliedSequence", failure.applied)
        })
    } catch (_: jp.co.translacat.languagelearning.features.learner.domain.exception.LearnerUnavailableException) {
        respond(HttpStatusCode.Forbidden, InternalApiError("LEARNER_UNAVAILABLE", "활성 상태의 학습자가 아닙니다."))
    } catch (failure: GrowthConflict) {
        respond(HttpStatusCode.Conflict, InternalApiError(failure.code, "성장 명령의 식별자·순서를 확인해 주세요."))
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
    } catch (failure: SQLException) {
        if (failure.errorCode == 1062) respond(HttpStatusCode.Conflict, InternalApiError("GROWTH_EVENT_CONFLICT", "성장 식별자가 충돌했습니다."))
        else throw failure
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
        if (output.size() + count > GrowthWire.MAX_BODY_BYTES) throw GrowthBodyTooLarge()
        output.write(buffer, 0, count)
    }
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
    return Json.parseToJsonElement(text).jsonObject
}
