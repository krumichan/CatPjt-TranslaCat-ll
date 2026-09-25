package jp.co.translacat.languagelearning.features.leveltest.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import jp.co.translacat.languagelearning.features.leveltest.api.dto.LevelAnswerRequestDto
import jp.co.translacat.languagelearning.features.leveltest.api.dto.LevelBaselineResponseDto
import jp.co.translacat.languagelearning.features.leveltest.api.dto.LevelCompletionsResponseDto
import jp.co.translacat.languagelearning.features.leveltest.api.dto.LevelTestStartRequestDto
import jp.co.translacat.languagelearning.features.leveltest.application.*
import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.exception.levelNotFound
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelAudioBytes
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelTestRules
import jp.co.translacat.languagelearning.shared.security.INTERNAL_AUTH
import jp.co.translacat.languagelearning.shared.security.InternalUserPrincipal
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

private const val ROOT = "/internal/v1/language-learning/level-test"
private val inputJson = Json { ignoreUnknownKeys = false; coerceInputValues = false }

/** 외부 API의 사용자 ID를 그대로 받지 않는다. 검증된 내부 사용자 토큰에서만 소유자를 가져온다. */
internal fun Route.levelTestRoutes(
    work: LevelTestUnitOfWork, sessions: LevelSessionService, questions: LevelQuestionService,
    answers: LevelAnswerService, reads: LevelReadService, audio: LevelAudioService,
) {
    // AI가 signed PUT으로 참고 음성만 업로드한다. 업로드 키/유효기간/바이트의 불변성을 별도로 검증한다.
    put("$ROOT/audio-uploads/{key}") {
        val key = call.parameters["key"] ?: levelNotFound()
        audio.upload(
            key, call.request.queryParameters["token"], call.boundedBytes(LevelTestRules.MAX_AUDIO_BYTES),
            call.request.contentType().toString(),
        )
        call.respond(HttpStatusCode.NoContent)
    }
    authenticate(INTERNAL_AUTH) {
        get("$ROOT/status") { call.respond(LevelResponseMapper.status(sessions.status(call.userId()))) }
        post("$ROOT/sessions") {
            val request = call.jsonBody<LevelTestStartRequestDto>()
            call.respond(
                LevelResponseMapper.session(sessions.start(call.userId(), request.type, request.idempotencyKey)),
            )
        }
        get("$ROOT/sessions/{sessionId}") {
            call.respond(
                LevelResponseMapper.session(sessions.session(call.userId(), call.id("sessionId"))),
            )
        }
        get("$ROOT/sessions/{sessionId}/current-item") {
            val user = call.userId();
            val id = call.id("sessionId")
            val item = questions.current(user, id)
            val reason =
                work.write(user) { records.submission(item.id)?.let { records.evaluation(it.id)?.data?.reasonCode } }
            call.respond(
                LevelResponseMapper.question(
                    sessions.session(user, id), item, reason, reads.healthy(item.data.referenceAudio?.objectKey),
                ),
            )
        }
        post("$ROOT/sessions/{sessionId}/items/{itemId}/answers") {
            val r = call.jsonBody<LevelAnswerRequestDto>()
            call.respond(
                LevelResponseMapper.answer(
                    answers.submitText(
                        call.userId(), call.id("sessionId"), call.id("itemId"), r.idempotencyKey, r.selectedOptionKey,
                        r.selectedOptionKeys, r.textAnswer,
                    ),
                ),
            )
        }
        // BE 외부 endpoint는 기존 multipart를 유지한다. 내부에서는 메타데이터와 제한된 원시 바이트만 전달한다.
        post("$ROOT/sessions/{sessionId}/items/{itemId}/answers/audio") {
            val r = answers.submitAudio(
                call.userId(), call.id("sessionId"), call.id("itemId"), call.request.queryParameters["idempotencyKey"],
                call.request.queryParameters["durationMs"]?.toIntOrNull(),
                call.boundedBytes(LevelTestRules.MAX_AUDIO_BYTES), call.request.contentType().toString(),
            )
            call.respond(LevelResponseMapper.audioAnswer(r))
        }
        post("$ROOT/sessions/{sessionId}/items/{itemId}/evaluation/retry") {
            call.respond(
                LevelResponseMapper.answer(answers.retry(call.userId(), call.id("sessionId"), call.id("itemId"))),
            )
        }
        get("$ROOT/sessions/{sessionId}/result") {
            call.respond(
                LevelResponseMapper.result(sessions.detail(call.userId(), call.id("sessionId")).session),
            )
        }
        get("$ROOT/history") { call.respond(sessions.history(call.userId()).map(LevelResponseMapper::summary)) }
        get("$ROOT/history/{sessionId}") {
            call.respond(
                LevelResponseMapper.detail(sessions.detail(call.userId(), call.id("sessionId")), reads),
            )
        }
        get("$ROOT/items/{itemId}/reference-audio") {
            call.respondAudio(
                reads.reference(call.userId(), call.id("itemId")),
            )
        }
        get("$ROOT/items/{itemId}/answer-audio") { call.respondAudio(reads.answer(call.userId(), call.id("itemId"))) }
        get("$ROOT/items/{itemId}/model-answer-audio") {
            call.respondAudio(
                reads.model(call.userId(), call.id("itemId")),
            )
        }
        get("$ROOT/baseline") {
            call.respond(
                LevelBaselineResponseDto(sessions.baseline(call.userId())?.let(LevelResponseMapper::baseline)),
            )
        }
        get("$ROOT/completions") {
            call.respond(
                LevelCompletionsResponseDto(sessions.history(call.userId()).map(LevelResponseMapper::completion)),
            )
        }
    }
}

private fun ApplicationCall.userId() = checkNotNull(principal<InternalUserPrincipal>()).user.userId
private fun ApplicationCall.id(name: String) = parameters[name]?.toLongOrNull()?.takeIf { it > 0 } ?: levelNotFound()
private suspend fun ApplicationCall.respondAudio(value: LevelAudioBytes) {
    response.header(HttpHeaders.CacheControl, "private, no-store")
    respondBytes(value.bytes, ContentType.parse(value.contentType))
}

private suspend inline fun <reified T> ApplicationCall.jsonBody(): T {
    if (!request.contentType().match(ContentType.Application.Json)) throw LevelTestException(
        "INVALID_REQUEST", 400, "application/json 요청이 필요합니다.",
    )
    return try {
        inputJson.decodeFromString<T>(boundedBytes(128 * 1024).toString(Charsets.UTF_8))
    } catch (_: SerializationException) {
        throw LevelTestException("INVALID_REQUEST", 400, "요청 JSON 형식과 필드를 확인해 주세요.")
    }
}

private suspend fun ApplicationCall.boundedBytes(limit: Int): ByteArray {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && (declared < 0 || declared > limit)) throw LevelTestException(
        "PAYLOAD_TOO_LARGE", 413, "요청 크기 제한을 초과했습니다.",
    )
    val input = receiveChannel();
    val output = ByteArrayOutputStream();
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.readAvailable(buffer, 0, buffer.size)
        if (count < 0) break
        if (output.size().toLong() + count > limit) throw LevelTestException(
            "PAYLOAD_TOO_LARGE", 413, "요청 크기 제한을 초과했습니다.",
        )
        if (count == 0) {
            kotlinx.coroutines.yield(); continue
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
