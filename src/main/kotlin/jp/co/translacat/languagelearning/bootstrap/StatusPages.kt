package jp.co.translacat.languagelearning.bootstrap

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import jp.co.translacat.languagelearning.features.learner.domain.exception.LearnerUnavailableException
import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.settings.domain.exception.SettingsPolicyNotInitializedException
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import jp.co.translacat.languagelearning.shared.http.InternalApiError
import jp.co.translacat.languagelearning.shared.security.InternalAuthorizationException
import kotlinx.coroutines.CancellationException

fun Application.configureStatusPages() {
    val logger = environment.log

    install(StatusPages) {
        exception<LevelTestException> { call, cause ->
            call.respond(
                HttpStatusCode.fromValue(cause.httpStatus),
                InternalApiError(cause.code, cause.message ?: "레벨 테스트 요청을 확인해 주세요."),
            )
        }

        exception<LearningBusinessException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                InternalApiError(
                    cause.code,
                    cause.message ?: "설정값을 확인해 주세요.",
                ),
            )
        }

        exception<InternalAuthorizationException> { call, _ ->
            call.respond(
                HttpStatusCode.Forbidden,
                InternalApiError(
                    "INTERNAL_ADMIN_REQUIRED",
                    "관리자 권한이 필요합니다.",
                ),
            )
        }

        exception<LearnerUnavailableException> { call, _ ->
            call.respond(
                HttpStatusCode.Forbidden,
                InternalApiError(
                    "LEARNER_UNAVAILABLE",
                    "이 학습자는 현재 이용할 수 없습니다.",
                ),
            )
        }

        exception<SettingsPolicyNotInitializedException> { call, _ ->
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                InternalApiError(
                    "SETTINGS_POLICY_UNAVAILABLE",
                    "필수 학습 설정을 확인해 주세요.",
                ),
            )
        }

        exception<BadRequestException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                InternalApiError(
                    "INVALID_REQUEST",
                    "요청 JSON 형식과 필드 값을 확인해 주세요.",
                ),
            )
        }

        exception<ContentTransformationException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                InternalApiError(
                    "INVALID_REQUEST",
                    "Content-Type과 요청 형식을 확인해 주세요.",
                ),
            )
        }

        exception<Throwable> { call, cause ->
            if (cause is CancellationException) {
                throw cause
            }

            // SQL, 토큰, 연결 문자열, 사용자 본문 등 민감한 원문을 로그와 응답에 노출하지 않는다.
            logger.error(
                "Unhandled settings request failure. type={}",
                cause.javaClass.name,
            )

            call.respond(
                HttpStatusCode.InternalServerError,
                InternalApiError(
                    "INTERNAL_ERROR",
                    "요청 처리 중 오류가 발생했습니다.",
                ),
            )
        }
    }
}
