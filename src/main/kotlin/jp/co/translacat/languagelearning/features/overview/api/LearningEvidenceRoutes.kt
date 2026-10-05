package jp.co.translacat.languagelearning.features.overview.api

import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.overview.application.LearningEvidenceQuery
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import jp.co.translacat.languagelearning.shared.security.INTERNAL_AUTH
import jp.co.translacat.languagelearning.shared.security.InternalUserPrincipal
import java.time.LocalDate

internal fun Route.learningEvidenceRoutes(query: LearningEvidenceQuery) {
    authenticate(INTERNAL_AUTH) {
        get("/internal/v1/language-learning/overview/evidence") {
            // 소유자는 인증에서만 얻고, 중복/알 수 없는 조건은 묵시적으로 버리지 않는다.
            val parameters = call.request.queryParameters
            val allowed = setOf("source", "learningLanguage", "from", "to", "resultKind", "policyVersion", "cursor", "limit")
            if (parameters.names().any { it !in allowed || parameters.getAll(it)?.size != 1 }) invalidEvidenceQuery()
            fun date(name: String): LocalDate = try {
                LocalDate.parse(parameters[name] ?: invalidEvidenceQuery())
            } catch (_: java.time.DateTimeException) {
                invalidEvidenceQuery()
            }
            val limit = parameters["limit"]?.let { it.toIntOrNull() ?: invalidEvidenceQuery() } ?: 25
            val userId = checkNotNull(call.principal<InternalUserPrincipal>()).user.userId
            call.respond(
                query.execute(
                    userId, parameters["source"], parameters["learningLanguage"], date("from"), date("to"),
                    parameters["resultKind"], parameters["policyVersion"], parameters["cursor"], limit,
                ),
            )
        }
    }
}

private fun invalidEvidenceQuery(): Nothing =
    throw LearningBusinessException("INVALID_REQUEST", "학습 근거 조회 조건을 확인해 주세요.")
