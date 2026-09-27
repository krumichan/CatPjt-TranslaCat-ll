package jp.co.translacat.languagelearning.features.speaking.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import jp.co.translacat.languagelearning.features.speaking.application.*
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingFailure
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingSttReportRecord
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingTopic
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingTurnRecord
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionFailure
import jp.co.translacat.languagelearning.shared.http.InternalApiError
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import jp.co.translacat.languagelearning.shared.security.INTERNAL_AUTH
import jp.co.translacat.languagelearning.shared.security.InternalUserPrincipal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.*

@Serializable
private data class CompleteRequest(val skipEvaluation: Boolean = false)
@Serializable
private data class ProcessEnvelope(
    val request: SpeakingProcessRequest, val audioBase64: String,
    val contentType: String? = null, val fileName: String? = null,
)

private val wireJson = Json { encodeDefaults = true }

internal fun Route.speakingRoutes(
    context: SpeakingContextService,
    opening: SpeakingOpeningExecution,
    reads: SpeakingReadService,
    sessions: SpeakingSessionState,
    turns: SpeakingTurnState,
    processing: SpeakingTurnExecution,
    topics: SpeakingTopicService,
    assistance: SpeakingAssistanceService,
    reports: SpeakingSttReportService,
    audio: SpeakingAudioService,
    evaluation: SpeakingEvaluationState,
    evaluationView: suspend (Long, Long) -> JsonElement,
    learningReports: SpeakingReportService,
    onEvaluationPending: () -> Unit,
) {
    authenticate(INTERNAL_AUTH) {
        route("/internal/v1/language-learning/speaking") {
            // 조회·관리 경계는 서명된 사용자와 관리자 권한을 사용하고 업무 판단은 해당 서비스에 위임한다.
            get("/report") {
                call.speakingResponse {
                    learningReports.report(
                        call.speakingUser().userId, call.request.queryParameters["from"]?.let(LocalDate::parse),
                        call.request.queryParameters["to"]?.let(LocalDate::parse),
                    )
                }
            }
            get("/sessions/{sessionId}/history") {
                call.speakingResponse {
                    learningReports.history(call.speakingUser().userId, call.speakingId("sessionId"))
                }
            }
            get("/topics") {
                call.speakingResponse {
                    JsonArray(
                        topics.list(
                            call.request.queryParameters["learningLanguage"], call.request.queryParameters["category"],
                        )
                            .map(::topicView),
                    )
                }
            }
            patch("/admin/topics/{topicId}") {
                call.speakingResponse {
                    if (!call.speakingUser().administrator) throw SpeakingFailure("FORBIDDEN", 403)
                    topicView(topics.update(call.speakingId("topicId"), call.receive()))
                }
            }

            // 세션 시작·완료는 저장된 결과를 조회해 반환한다. 평가 알림은 완료 상태 저장 이후에 보낸다.
            get("/sessions/today/status") { call.speakingResponse { reads.today(call.speakingUser().userId) } }
            get("/sessions/active") { call.speakingResponse { reads.active(call.speakingUser().userId) ?: JsonNull } }
            post("/sessions") {
                call.speakingResponse {
                    val user = call.speakingUser().userId
                    val session = opening.start(context.create(user, call.receive()))
                    reads.session(user, session.id)
                }
            }
            get("/sessions/{sessionId}") {
                call.speakingResponse {
                    reads.detail(
                        call.speakingUser().userId, call.speakingId("sessionId"),
                    )
                }
            }
            post("/sessions/{sessionId}/complete") {
                call.speakingResponse {
                    val user = call.speakingUser().userId
                    val session = sessions.complete(
                        user, call.speakingId("sessionId"), call.receive<CompleteRequest>().skipEvaluation,
                    )
                    onEvaluationPending()
                    reads.session(user, session.id)
                }
            }

            // 업로드 허가와 실제 음성 처리를 분리하고 모든 하위 ID를 LL 공개 ID 경계에서 확인한다.
            post("/sessions/{sessionId}/turns/upload-url") {
                call.speakingResponse {
                    val id = call.speakingId("sessionId")
                    grantView(id, turns.grant(call.speakingUser().userId, id, call.receive()))
                }
            }
            post("/sessions/{sessionId}/turns/{turnId}/rerecord/upload-url") {
                call.speakingResponse {
                    val id = call.speakingId("sessionId")
                    grantView(id, turns.rerecordGrant(call.speakingUser().userId, id, call.speakingId("turnId")))
                }
            }
            post("/sessions/{sessionId}/turns") {
                call.speakingResponse {
                    // BE가 외부 multipart를 이 내부 전송 envelope로 옮기며 학습 판정은 여기서 실행한다.
                    val input = call.processEnvelope()
                    val turn =
                        input.request.turnId?.let { LearningPublicId.decode(it.toString()) } ?: throw SpeakingFailure(
                            "INVALID_AUDIO",
                        )
                    val bytes = try {
                        Base64.getDecoder().decode(input.audioBase64)
                    } catch (_: IllegalArgumentException) {
                        throw SpeakingFailure("INVALID_AUDIO")
                    }

                    // 음성 처리의 상태 변경을 마친 뒤 작업자를 깨우고 저장된 최신 발화를 반환한다.
                    val user = call.speakingUser().userId
                    val session = call.speakingId("sessionId")
                    processing.process(user, session, turn, input.request, bytes, input.contentType, input.fileName)
                    onEvaluationPending()
                    reads.turn(user, session, turn)
                }
            }
            get("/sessions/{sessionId}/turns/{turnId}") {
                call.speakingResponse {
                    reads.turn(call.speakingUser().userId, call.speakingId("sessionId"), call.speakingId("turnId"))
                }
            }
            post("/sessions/{sessionId}/turns/{turnId}/retry") {
                call.speakingResponse {
                    val user = call.speakingUser().userId
                    val session = call.speakingId("sessionId")
                    val turn = call.speakingId("turnId")
                    processing.retry(user, session, turn)
                    onEvaluationPending()
                    reads.turn(user, session, turn)
                }
            }
            post("/sessions/{sessionId}/turns/{turnId}/exclude") {
                call.speakingResponse {
                    val user = call.speakingUser().userId
                    val session = call.speakingId("sessionId")
                    val turn = call.speakingId("turnId")
                    turns.exclude(user, session, turn)
                    reads.turn(user, session, turn)
                }
            }

            // 도움·평가와 문항별 제출은 기존 수동 재시도 정책을 서비스 계층에서 적용한다.
            post("/sessions/{sessionId}/assistance") {
                call.speakingResponse {
                    assistance.get(call.speakingUser().userId, call.speakingId("sessionId"), call.receive())
                }
            }
            get("/sessions/{sessionId}/evaluation") {
                call.speakingResponse {
                    evaluationView(call.speakingUser().userId, call.speakingId("sessionId"))
                }
            }
            post("/sessions/{sessionId}/evaluation/retry") {
                call.speakingResponse {
                    evaluation.retry(call.speakingUser().userId, call.speakingId("sessionId"), 0)
                    onEvaluationPending()
                    JsonNull
                }
            }
            get("/sessions/{sessionId}/read-aloud/problems") {
                call.speakingResponse {
                    reads.problems(call.speakingUser().userId, call.speakingId("sessionId"))
                }
            }
            post("/sessions/{sessionId}/read-aloud/problems/{problemIndex}/evaluate") {
                call.speakingResponse {
                    val user = call.speakingUser().userId
                    val session = call.speakingId("sessionId")
                    val problem = call.problemIndex()
                    sessions.submitProblem(user, session, problem)
                    onEvaluationPending()
                    reads.problems(user, session).first { it.jsonObject["problemIndex"]?.jsonPrimitive?.int == problem }
                }
            }
            post("/sessions/{sessionId}/read-aloud/problems/{problemIndex}/evaluation/retry") {
                call.speakingResponse {
                    val user = call.speakingUser().userId
                    val session = call.speakingId("sessionId")
                    val problem = call.problemIndex()
                    evaluation.retry(user, session, problem)
                    onEvaluationPending()
                    reads.problems(user, session).first { it.jsonObject["problemIndex"]?.jsonPrimitive?.int == problem }
                }
            }

            // 신고와 오디오 조회도 사용자 소유권을 확인하며 원문을 경로·진단 로그에 넣지 않는다.
            post("/sessions/{sessionId}/turns/{turnId}/stt-reports") {
                call.speakingResponse {
                    reportView(
                        reports.create(
                            call.speakingUser().userId, call.speakingId("sessionId"), call.speakingId("turnId"),
                            call.receive(),
                        ),
                    )
                }
            }
            get("/stt-reports/{reportId}") {
                call.speakingResponse {
                    reportView(
                        reports.get(call.speakingUser().userId, call.speakingId("reportId")),
                    )
                }
            }
            post("/stt-reports/{reportId}/support") {
                call.speakingResponse {
                    reportView(
                        reports.support(call.speakingUser().userId, call.speakingId("reportId")),
                    )
                }
            }
            get("/sessions/{sessionId}/audio/opening") {
                call.speakingAudio {
                    audio.get(
                        call.speakingUser().userId, call.speakingId("sessionId"), null, "OPENING",
                    )
                }
            }
            get("/sessions/{sessionId}/turns/{turnId}/audio") {
                call.speakingAudio {
                    audio.get(
                        call.speakingUser().userId, call.speakingId("sessionId"), call.speakingId("turnId"),
                        "ASSISTANT",
                    )
                }
            }
            get("/sessions/{sessionId}/turns/{turnId}/audio/user") {
                call.speakingAudio {
                    audio.get(
                        call.speakingUser().userId, call.speakingId("sessionId"), call.speakingId("turnId"), "USER",
                    )
                }
            }
        }
    }
}

private fun ApplicationCall.speakingUser() = checkNotNull(principal<InternalUserPrincipal>()).user
private fun ApplicationCall.speakingId(name: String) = LearningPublicId.decode(parameters[name])
private fun ApplicationCall.problemIndex() = parameters["problemIndex"]?.toIntOrNull()?.takeIf { it in 1..5 }
    ?: throw SpeakingFailure("EVALUATION_FAILED")

private suspend fun ApplicationCall.processEnvelope(): ProcessEnvelope {
    // 일반 request DTO보다 큰 오디오 envelope만 별도 상한을 적용해 무제한 Base64 할당을 막는다.
    val channel = receiveChannel()
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (!channel.isClosedForRead) {
        val read = channel.readAvailable(buffer)
        if (read < 0) break
        if (bytes.size() + read > 14_100_000) throw SpeakingFailure("INVALID_AUDIO")
        bytes.write(buffer, 0, read)
    }
    return Json.decodeFromString(bytes.toString(Charsets.UTF_8))
}

private suspend fun ApplicationCall.speakingResponse(action: suspend () -> JsonElement) {
    try {
        respond(action())
    } catch (failure: SpeakingFailure) {
        respond(HttpStatusCode.fromValue(failure.status), InternalApiError(failure.code, "Speaking 요청 상태를 확인해 주세요."))
    } catch (failure: ModelExecutionFailure) {
        respond(HttpStatusCode.fromValue(failure.status), InternalApiError(failure.code, "모델 실행 경로를 확인해 주세요."))
    }
}

private suspend fun ApplicationCall.speakingAudio(action: suspend () -> SpeakingAudioResponse) {
    try {
        val audio = action()
        respondBytes(audio.bytes, ContentType.parse(audio.contentType))
    } catch (failure: SpeakingFailure) {
        respond(HttpStatusCode.fromValue(failure.status), InternalApiError(failure.code, "Speaking Audio를 찾을 수 없습니다."))
    }
}

private fun grantView(sessionId: Long, turn: SpeakingTurnRecord) = buildJsonObject {
    put("turnId", LearningPublicId.encode(turn.id)); put("turnIndex", turn.turnIndex); put(
    "uploadToken", turn.uploadToken,
)
    put("uploadUrl", "/api/v1/language-learning/speaking/sessions/${LearningPublicId.encode(sessionId)}/turns")
    put("expiresAt", turn.uploadExpiresAt.toString())
}

private fun topicView(topic: SpeakingTopic) = JsonObject(
    wireJson.encodeToJsonElement(topic).jsonObject.filterKeys { it != "active" } +
        ("id" to JsonPrimitive(LearningPublicId.encode(topic.id))),
)

private fun reportView(report: SpeakingSttReportRecord) = buildJsonObject {
    put("id", LearningPublicId.encode(report.id)); put("reportReference", report.reference)
    put("sessionId", LearningPublicId.encode(report.sessionId)); put("turnId", LearningPublicId.encode(report.turnId))
    put("reportType", report.reportType); put("reportStatus", report.status)
    put("expectedText", report.expectedText?.let(::JsonPrimitive) ?: JsonNull)
    put("audioAnalysisConsent", report.audioAnalysisConsent)
    put("audioRetentionUntil", report.audioRetentionUntil?.toString()?.let(::JsonPrimitive) ?: JsonNull)
    put("supportRequested", report.supportRequested); put(
    "supportReference", report.supportReference?.let(::JsonPrimitive) ?: JsonNull,
)
    put("resolvedAt", report.resolvedAt?.toString()?.let(::JsonPrimitive) ?: JsonNull)
}
