package jp.co.translacat.languagelearning.features.settings.api

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.settings.api.dto.AdminSettingUpdateRequestDto
import jp.co.translacat.languagelearning.features.settings.api.dto.UserSettingUpdateRequestDto
import jp.co.translacat.languagelearning.features.settings.application.SettingsOperations
import jp.co.translacat.languagelearning.shared.security.INTERNAL_AUTH
import jp.co.translacat.languagelearning.shared.security.InternalAuthorizationException
import jp.co.translacat.languagelearning.shared.security.InternalUserPrincipal

/** 외부 FE URL이 아니다. Spring BE가 인증 후 발급한 전용 내부 JWT로만 호출한다. */
internal fun Route.settingsRoutes(operations: SettingsOperations) {
    authenticate(INTERNAL_AUTH) {
        get("/internal/v1/language-learning/settings") {
            call.respond(operations.getUser(call.verifiedUser().userId).toResponse())
        }
        patch("/internal/v1/language-learning/settings") {
            val user = call.verifiedUser()
            val change = call.receive<UserSettingUpdateRequestDto>().toChange()
            call.respond(operations.updateUser(user.userId, change).toResponse())
        }
        get("/internal/v1/admin/language-learning/settings") {
            call.requireAdministrator()
            call.respond(operations.getAdmin().toResponse())
        }
        patch("/internal/v1/admin/language-learning/settings") {
            val user = call.requireAdministrator()
            val change = call.receive<AdminSettingUpdateRequestDto>().toChange()
            call.respond(operations.updateAdmin(user.userId, change).toResponse())
        }
    }
}

private fun ApplicationCall.verifiedUser() = checkNotNull(principal<InternalUserPrincipal>()).user
private fun ApplicationCall.requireAdministrator() = verifiedUser().also {
    if (!it.administrator) throw InternalAuthorizationException()
}
