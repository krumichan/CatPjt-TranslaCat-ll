package jp.co.translacat.languagelearning.features.settings.api

import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskType
import jp.co.translacat.languagelearning.features.settings.api.dto.SelectionDeliveryRequestDto
import jp.co.translacat.languagelearning.features.settings.api.dto.SelectionDeliveryResponseDto
import jp.co.translacat.languagelearning.features.settings.application.RememberListeningSelection
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import jp.co.translacat.languagelearning.shared.security.INTERNAL_AUTH
import jp.co.translacat.languagelearning.shared.security.InternalUserPrincipal
import java.time.LocalDateTime

/** 사용자 ID는 토큰에서만 얻는다. FE에 이 URL을 그대로 노출하지 않는다. */
internal fun Route.settingsSelectionRoute(operation: RememberListeningSelection) {
    authenticate(INTERNAL_AUTH) {
        post("/internal/v1/language-learning/settings/listening-selection") {
            val userId = checkNotNull(call.principal<InternalUserPrincipal>()).user.userId
            val request = call.receive<SelectionDeliveryRequestDto>()
            val revision = try {
                LocalDateTime.parse(request.expectedRevision)
            } catch (_: Exception) {
                throw LearningBusinessException("INVALID_REQUEST", "Settings revision 형식이 유효하지 않습니다.")
            }
            val types = try {
                request.taskTypes.map { it?.let(ListeningTaskType::valueOf) }
            } catch (_: IllegalArgumentException) {
                throw LearningBusinessException("INVALID_REQUEST", "Listening Task가 유효하지 않습니다.")
            }
            if (request.eventId <= 0 || revision.nano % 1000 != 0 || revision.year !in 1000..9999) {
                throw LearningBusinessException("INVALID_REQUEST", "Settings 이벤트 식별자/정밀도가 유효하지 않습니다.")
            }
            call.respond(SelectionDeliveryResponseDto(operation.execute(userId, request.eventId, revision, types).name))
        }
    }
}
