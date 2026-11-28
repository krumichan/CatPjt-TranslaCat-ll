package jp.co.translacat.languagelearning.features.autopractice.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.autopractice.application.AutoPracticeService
import jp.co.translacat.languagelearning.features.autopractice.domain.AutoPracticeArea
import jp.co.translacat.languagelearning.features.autopractice.domain.AutoPracticePlanInput
import jp.co.translacat.languagelearning.shared.http.InternalApiError
import jp.co.translacat.languagelearning.shared.security.INTERNAL_AUTH
import jp.co.translacat.languagelearning.shared.security.InternalUserPrincipal
import kotlinx.serialization.Serializable

@Serializable
internal data class AutoPracticeLimitsRequest(val limits: Map<AutoPracticeArea, Int>)

/** 사용자 플랜과 관리자 상한을 내부 인증에서 분리하며 준비 목록은 저장 데이터만 읽는다. */
internal fun Route.autoPracticeRoutes(service: AutoPracticeService) {
    authenticate(INTERNAL_AUTH) {
        route("/internal/v1/language-learning/auto-practice") {
            get {
                call.respond(service.workspace(call.owner().user.userId))
            }
            post("/manual") {
                call.respond(service.prepareManually(call.owner().user.userId))
            }
            post("/plans") {
                call.respond(service.save(call.owner().user.userId, null, call.receive<AutoPracticePlanInput>()))
            }
            put("/plans/{id}") {
                call.respond(service.save(
                    call.owner().user.userId, checkNotNull(call.parameters["id"]), call.receive<AutoPracticePlanInput>(),
                ))
            }
            delete("/plans/{id}") {
                service.delete(call.owner().user.userId, checkNotNull(call.parameters["id"]))
                call.respond(HttpStatusCode.NoContent)
            }

            // 관리자 경계는 사용자 ID나 요청 본문의 역할 대신 검증된 서비스 JWT를 사용한다.
            get("/admin/limits") {
                if (!call.requireAdmin()) return@get
                call.respond(AutoPracticeLimitsRequest(service.limits()))
            }
            put("/admin/limits") {
                if (!call.requireAdmin()) return@put
                val values = call.receive<AutoPracticeLimitsRequest>()
                call.respond(AutoPracticeLimitsRequest(service.updateLimits(values.limits)))
            }
        }
    }
}

private fun ApplicationCall.owner(): InternalUserPrincipal = checkNotNull(principal<InternalUserPrincipal>())

/** 관리자 요청이 아니면 변경 경로에 도달하기 전에 종료한다. */
private suspend fun ApplicationCall.requireAdmin(): Boolean {
    if (owner().user.administrator) return true
    respond(HttpStatusCode.Forbidden, InternalApiError("INTERNAL_ADMIN_REQUIRED", "관리자 권한이 필요합니다."))
    return false
}
