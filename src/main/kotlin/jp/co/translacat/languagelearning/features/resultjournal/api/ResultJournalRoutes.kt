package jp.co.translacat.languagelearning.features.resultjournal.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import jp.co.translacat.languagelearning.features.resultjournal.api.dto.ResultEnvelopeDto
import jp.co.translacat.languagelearning.features.resultjournal.api.dto.ResultReceiptDto
import jp.co.translacat.languagelearning.features.resultjournal.application.AcceptLearningResult
import jp.co.translacat.languagelearning.features.resultjournal.domain.exception.ResultJournalConflict
import jp.co.translacat.languagelearning.shared.http.InternalApiError
import jp.co.translacat.languagelearning.shared.security.RESULT_JOURNAL_AUTH
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.sql.SQLException
import java.time.format.DateTimeParseException

internal fun Route.resultJournalRoutes(accept: AcceptLearningResult) {
    authenticate(RESULT_JOURNAL_AUTH) {
        post("/internal/v1/learning-results") {
            if (!call.request.contentType().match(ContentType.Application.Json)) {
                call.respond(HttpStatusCode.UnsupportedMediaType, InternalApiError("RESULT_JSON_REQUIRED", "JSON 요청이 필요합니다."))
                return@post
            }
            try {
                val dto = Json.decodeFromString<ResultEnvelopeDto>(call.boundedResultBody())
                val event = dto.toDomain()
                ResultPayloadValidator.validate(event)
                val receipt = accept.execute(event)
                call.respond(ResultReceiptDto(receipt.sourceInstanceId, receipt.eventId, receipt.userId, receipt.sequence,
                    receipt.payloadSha256, receipt.outcome.name))
            } catch (_: ResultBodyTooLarge) {
                call.respond(HttpStatusCode.PayloadTooLarge, InternalApiError("RESULT_BODY_TOO_LARGE", "결과 본문이 너무 큽니다."))
            } catch (failure: ResultJournalConflict) {
                call.respond(HttpStatusCode.Conflict, InternalApiError(failure.code, "결과 순서 또는 식별자가 일치하지 않습니다."))
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, InternalApiError("RESULT_INVALID", "결과 본문을 확인해 주세요."))
            } catch (_: CharacterCodingException) {
                call.respond(HttpStatusCode.BadRequest, InternalApiError("RESULT_INVALID", "UTF-8 본문이 필요합니다."))
            } catch (_: DateTimeParseException) {
                call.respond(HttpStatusCode.BadRequest, InternalApiError("RESULT_INVALID", "결과 시각을 확인해 주세요."))
            } catch (failure: SQLException) {
                // 서로 다른 user stream이 같은 eventId를 동시에 보내도 원문 SQL은 노출하지 않는다.
                if (failure.errorCode == 1062) {
                    call.respond(HttpStatusCode.Conflict, InternalApiError("RESULT_EVENT_CONFLICT", "결과 식별자가 충돌했습니다."))
                } else throw failure
            }
        }
    }
}

private class ResultBodyTooLarge : RuntimeException()
private suspend fun ApplicationCall.boundedResultBody(): String {
    val channel = receiveChannel()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val size = channel.readAvailable(buffer)
        if (size == -1) break
        if (output.size() + size > 2_097_152) throw ResultBodyTooLarge()
        output.write(buffer, 0, size)
    }
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
}
