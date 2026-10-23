package jp.co.translacat.languagelearning.features.accountdeletion.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import jp.co.translacat.languagelearning.features.accountdeletion.application.*
import jp.co.translacat.languagelearning.shared.http.InternalApiError
import jp.co.translacat.languagelearning.shared.security.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

@Serializable
private data class AccountDeletionCapability(val version: String = "ll-account-deletion-v1", val ready: Boolean)

internal fun Route.accountDeletionRoutes(service: AccountDeletionService, storageReady: () -> Boolean) {
    authenticate(ACCOUNT_DELETION_AUTH) {
        route("/internal/v1/language-learning/accounts/{userId}/deletion") {
            get("/capabilities") {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                try {
                    call.owner()
                    val ready = storageReady() && service.ready()
                    call.respond(if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                        AccountDeletionCapability(ready = ready))
                } catch (failure: AccountDeletionFailure) {
                    call.failure(failure)
                }
            }
            post {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                try {
                    val user = call.owner()
                    if (!storageReady() || !service.ready()) throw AccountDeletionFailure("ACCOUNT_DELETION_NOT_READY", 503)
                    call.respond(service.delete(user, call.deletionBody()))
                } catch (failure: AccountDeletionFailure) {
                    call.failure(failure)
                }
            }
        }
    }
}

private fun ApplicationCall.owner(): Long {
    val raw = parameters["userId"] ?: throw AccountDeletionFailure("ACCOUNT_DELETION_INVALID", 400)
    if (!Regex("[1-9][0-9]{0,18}").matches(raw)) throw AccountDeletionFailure("ACCOUNT_DELETION_INVALID", 400)
    val user = raw.toLongOrNull() ?: throw AccountDeletionFailure("ACCOUNT_DELETION_INVALID", 400)
    if (principal<AccountDeletionPrincipal>()?.userId != user) throw AccountDeletionFailure("ACCOUNT_DELETION_FORBIDDEN", 403)
    return user
}

private suspend fun ApplicationCall.failure(value: AccountDeletionFailure) {
    respond(HttpStatusCode.fromValue(value.status), InternalApiError(value.code, "계정 삭제 요청을 확인해 주세요."))
}

private suspend fun ApplicationCall.deletionBody(): AccountDeletionRequest {
    if (!request.contentType().match(ContentType.Application.Json)) throw AccountDeletionFailure("ACCOUNT_DELETION_INVALID", 415)
    // 전송 크기/문자 인코딩/중복·알 수 없는 필드를 먼저 제한하며 사용자 입력은 오류 응답에 넣지 않는다.
    val input = receiveChannel()
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(2049)
    while (true) {
        val count = input.readAvailable(buffer)
        if (count == -1) break
        if (bytes.size() + count > 2048) throw AccountDeletionFailure("ACCOUNT_DELETION_INVALID", 413)
        bytes.write(buffer, 0, count)
    }
    try {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        val json = Json.parseToJsonElement(text) as? JsonObject ?: error("invalid")
        if (json.keys != setOf("operationId", "requestedAtUtc") || topLevelMemberCount(text) != 2) error("invalid")
        fun string(name: String): String {
            val value = json[name] as? JsonPrimitive ?: error("invalid")
            if (!value.isString) error("invalid")
            return value.content
        }
        return AccountDeletionRequest(string("operationId"), string("requestedAtUtc"))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw AccountDeletionFailure("ACCOUNT_DELETION_INVALID", 400)
    }
}

/** 유효한 JSON에서 최상위 멤버만 센다. 이스케이프된 이름의 중복도 원문 표기와 무관하게 거부한다. */
private fun topLevelMemberCount(text: String): Int {
    var quoted = false
    var escaped = false
    var depth = 0
    var members = 0
    for (character in text) {
        if (quoted) {
            if (escaped) escaped = false
            else when (character) {
                '\\' -> escaped = true
                '"' -> quoted = false
            }
        } else {
            when (character) {
                '"' -> quoted = true
                '{', '[' -> depth++
                '}', ']' -> depth--
                ':' -> if (depth == 1) members++
            }
        }
    }
    return members
}
