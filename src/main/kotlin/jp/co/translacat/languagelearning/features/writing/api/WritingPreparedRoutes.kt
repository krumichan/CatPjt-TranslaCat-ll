package jp.co.translacat.languagelearning.features.writing.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import jp.co.translacat.languagelearning.features.writing.application.*
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingSet
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingSetStatus
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.shared.http.InternalApiError
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import jp.co.translacat.languagelearning.shared.security.INTERNAL_AUTH
import jp.co.translacat.languagelearning.shared.security.InternalUserPrincipal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.time.LocalDate

private const val ROOT = "/internal/v1/language-learning/writing/daily"

internal data class WritingAnswerRouteContext(
    val today: LocalDate,
    val reviewDays: Int,
    val aiEvaluationEnabled: Boolean,
    val originLanguage: String,
    val learningLanguage: String,
)

/** LL이 준비한 문맥으로 세트를 만들고 모델 실행을 비동기로 시작한다. */
internal fun Route.writingPreparedRoutes(
    state: WritingGenerationState,
    worker: WritingGenerationWorker,
    read: WritingReadService,
    readContext: suspend (Long) -> Pair<LocalDate, Int>,
    answers: WritingAnswerState,
    evaluation: WritingEvaluationWorker,
    answerContext: suspend (Long) -> WritingAnswerRouteContext,
    regenerationAllowed: suspend () -> Boolean,
    createSet: suspend (Long, WritingType) -> WritingSet,
) {
    authenticate(INTERNAL_AUTH) {
        post("$ROOT/sets") {
            val userId = checkNotNull(call.principal<InternalUserPrincipal>()).user.userId
            val type = try {
                val body = call.readWritingBody()
                require(body.keys == setOf("writingType"))
                WritingType.valueOf(body.getValue("writingType").jsonPrimitive.content)
            } catch (failure: RuntimeException) {
                if (failure is CancellationException) throw failure
                // 요청 원문 없이 실패 지점과 예외 종류만 남겨 BE 직렬화 계약을 추적한다.
                val line = failure.stackTrace.firstOrNull {
                    it.className.contains("WritingPreparedRoutes")
                }?.lineNumber
                call.application.environment.log.warn(
                    "Writing prepared request rejected. line={} type={}",
                    line, failure.javaClass.simpleName,
                )
                call.respond(
                    HttpStatusCode.BadRequest,
                    InternalApiError(
                        "WRITING_REQUEST_INVALID",
                        "Writing 생성 요청을 확인해 주세요.",
                    ),
                )
                return@post
            }
            // 같은 날짜·유형의 요청은 먼저 저장된 snapshot을 유지하고 중복 dispatch는 lease가 막는다.
            val set = createSet(userId, type)
            if (set.status == WritingSetStatus.GENERATING) {
                val stored = Json.parseToJsonElement(set.snapshotJson).jsonObject
                val app = call.application
                app.launch(CoroutineName("writing-generate-${set.id}")) {
                    try {
                        worker.process(userId, set.id, stored)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        app.environment.log.warn(
                            "Writing dispatch failed. type={}",
                            failure.javaClass.simpleName,
                        )
                    }
                }
            }
            val (today, reviewDays) = readContext(userId)
            call.respond(
                if (set.status == WritingSetStatus.GENERATING) HttpStatusCode.Accepted else HttpStatusCode.OK,
                checkNotNull(read.byId(userId, set.id, today, reviewDays)),
            )
        }
        get("$ROOT/sets/{setId}") {
            val userId = checkNotNull(call.principal<InternalUserPrincipal>()).user.userId
            val setId = LearningPublicId.decode(call.parameters["setId"])
            val (today, reviewDays) = readContext(userId)
            val result = read.byId(userId, setId, today, reviewDays)
            if (result == null) call.respond(
                HttpStatusCode.BadRequest,
                checkNotNull(writingStateError("WRITING_SET_NOT_FOUND")),
            )
            else call.respond(result)
        }
        post("$ROOT/sets/{setId}/retry-generation") {
            val userId = checkNotNull(call.principal<InternalUserPrincipal>()).user.userId
            val setId = LearningPublicId.decode(call.parameters["setId"])
            val set = state.retry(userId, setId)
            if (set == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    checkNotNull(writingStateError("WRITING_SET_NOT_FOUND")),
                )
                return@post
            }

            // 명시 재시도에서만 PARTIAL/FAILED를 생성 상태로 되돌리고 저장된 snapshot을 사용한다.
            if (set.status == WritingSetStatus.GENERATING) {
                val stored = Json.parseToJsonElement(set.snapshotJson).jsonObject
                val app = call.application
                app.launch(CoroutineName("writing-retry-${set.id}")) {
                    try {
                        worker.process(userId, set.id, stored)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        app.environment.log.warn(
                            "Writing retry dispatch failed. type={}",
                            failure.javaClass.simpleName,
                        )
                    }
                }
            }
            val (today, reviewDays) = readContext(userId)
            call.respond(
                if (set.status == WritingSetStatus.GENERATING) HttpStatusCode.Accepted else HttpStatusCode.OK,
                checkNotNull(read.byId(userId, set.id, today, reviewDays)),
            )
        }
        post("$ROOT/sets/{setId}/regenerate") {
            val userId = checkNotNull(call.principal<InternalUserPrincipal>()).user.userId
            val setId = LearningPublicId.decode(call.parameters["setId"])
            if (!regenerationAllowed()) {
                call.respond(HttpStatusCode.BadRequest, checkNotNull(writingStateError("WRITING_GENERATION_DISABLED")))
                return@post
            }

            // 기존 BE와 같이 재생성은 검증된 모든 교체 문항의 원자 게시가 끝날 때까지 기다린다.
            val result = try {
                worker.regenerateFromSnapshot(userId, setId)
            } catch (failure: RuntimeException) {
                if (call.respondWritingStateFailure(failure)) return@post
                throw failure
            }
            if (result.failureCode != null) {
                val failure = writingPublicFailure(result.failureCode, result.failureStage)
                call.respond(
                    HttpStatusCode.fromValue(failure.status),
                    InternalApiError(failure.code, "Writing 재생성에 실패했습니다. 기존 문항은 보존되었습니다."),
                )
                return@post
            }
            val (today, reviewDays) = readContext(userId)
            call.respond(checkNotNull(read.byId(userId, setId, today, reviewDays)))
        }
        get("$ROOT/history/{date}") {
            val userId = checkNotNull(call.principal<InternalUserPrincipal>()).user.userId
            val date = runCatching { LocalDate.parse(call.parameters["date"]) }.getOrNull()
            val type = runCatching { WritingType.valueOf(call.request.queryParameters["writingType"] ?: "FREE") }
                .getOrNull()
            if (date == null || type == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    InternalApiError(
                        "WRITING_REQUEST_INVALID",
                        "Writing 조회 날짜와 유형을 확인해 주세요.",
                    ),
                )
                return@get
            }
            val (today, reviewDays) = readContext(userId)
            val result = read.byDate(userId, date, type, today, reviewDays)
            if (result == null) call.respond(
                HttpStatusCode.NotFound,
                InternalApiError("WRITING_SET_NOT_FOUND", "Writing 세트를 찾을 수 없습니다."),
            )
            else call.respond(result)
        }
        post("$ROOT/items/{itemId}/answers") {
            val userId = checkNotNull(call.principal<InternalUserPrincipal>()).user.userId
            val itemId = LearningPublicId.decode(call.parameters["itemId"])
            val body = try {
                call.readWritingBody()
            } catch (failure: RuntimeException) {
                if (failure is CancellationException) throw failure
                call.respond(
                    HttpStatusCode.BadRequest,
                    InternalApiError(
                        "WRITING_REQUEST_INVALID",
                        "Writing 답변 요청을 확인해 주세요.",
                    ),
                )
                return@post
            }
            val answerText = (body["answer"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val revisionValue = body["contentRevision"]
            if (answerText == null) {
                call.respond(HttpStatusCode.BadRequest, checkNotNull(writingStateError("WRITING_ANSWER_REQUIRED")))
                return@post
            }
            if (revisionValue != null && revisionValue !is JsonNull &&
                (revisionValue !is JsonPrimitive || !revisionValue.isString)
            ) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    InternalApiError(
                        "WRITING_REQUEST_INVALID",
                        "Writing 답변 요청을 확인해 주세요.",
                    ),
                )
                return@post
            }
            val context = answerContext(userId)

            // 답변·PENDING 평가는 한 트랜잭션에 보관하고 외부 모델 호출은 응답 뒤에 시작한다.
            val saved = try {
                answers.submit(
                    userId, itemId, answerText,
                    if (revisionValue == null || revisionValue is JsonNull) null else revisionValue.jsonPrimitive.content,
                    context.today,
                    context.reviewDays, context.aiEvaluationEnabled,
                )
            } catch (failure: RuntimeException) {
                if (call.respondWritingStateFailure(failure)) return@post
                throw failure
            }
            val result = checkNotNull(read.answer(userId, saved.id))
            val app = call.application
            app.launch(CoroutineName("writing-evaluate-${saved.id}")) {
                try {
                    evaluation.process(
                        userId, saved.id, context.originLanguage,
                        context.learningLanguage, context.today,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    app.environment.log.warn(
                        "Writing evaluation dispatch failed. type={}",
                        failure.javaClass.simpleName,
                    )
                }
            }
            call.respond(result)
        }
        post("$ROOT/items/{itemId}/evaluation/resume") {
            val userId = checkNotNull(call.principal<InternalUserPrincipal>()).user.userId
            val itemId = LearningPublicId.decode(call.parameters["itemId"])
            val context = answerContext(userId)
            val answerId = try {
                answers.pendingToday(userId, itemId, context.today)
            } catch (failure: RuntimeException) {
                if (call.respondWritingStateFailure(failure)) return@post
                throw failure
            }
            if (answerId != null) {
                val app = call.application
                app.launch(CoroutineName("writing-evaluate-$answerId")) {
                    try {
                        evaluation.process(
                            userId, answerId, context.originLanguage,
                            context.learningLanguage, context.today,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        app.environment.log.warn(
                            "Writing evaluation resume failed. type={}",
                            failure.javaClass.simpleName,
                        )
                    }
                }
            }
            call.respond(HttpStatusCode.OK)
        }
    }
}

private suspend fun ApplicationCall.respondWritingStateFailure(failure: RuntimeException): Boolean {
    val code = failure.message ?: return false
    val error = writingStateError(code) ?: return false
    respond(HttpStatusCode.BadRequest, error)
    return true
}

private suspend fun ApplicationCall.readWritingBody(): JsonObject {
    require(request.contentType().match(ContentType.Application.Json))
    val limit = 256 * 1024
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    require(declared == null || declared in 0..limit.toLong())
    val input = receiveChannel()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val size = input.readAvailable(buffer, 0, buffer.size)
        if (size < 0) break
        require(output.size() + size <= limit)
        if (size == 0) {
            kotlinx.coroutines.yield()
            continue
        }
        output.write(buffer, 0, size)
    }
    return Json.parseToJsonElement(output.toString(Charsets.UTF_8)).jsonObject
}
