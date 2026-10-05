package jp.co.translacat.languagelearning.features.overview.application

import jp.co.translacat.languagelearning.features.growth.application.GrowthUnitOfWork
import jp.co.translacat.languagelearning.features.growth.domain.model.*
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Base64

/** 조회 기간은 필터이며 평가 주기가 아니다. 이 경계는 저장소 읽기만 사용한다. */
internal class LearningEvidenceQuery(private val work: GrowthUnitOfWork) {
    suspend fun execute(
        userId: Long, source: String?, language: String?, from: LocalDate, to: LocalDate,
        kind: String?, policy: String?, cursor: String?, limit: Int,
    ): JsonObject {
        // 소유자·필터·기술적 상한을 먼저 확정한다. 알 수 없는 정책을 legacy로 바꾸지 않는다.
        val selectedSource = source?.takeUnless { it == "ALL" }
        if (userId <= 0 || selectedSource != null && selectedSource !in HistoryProjection.sources ||
            language != null && !Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*").matches(language) ||
            language != null && language.length > 20 || limit !in 1..50 ||
            ChronoUnit.DAYS.between(from, to) !in 0..365 ||
            kind != null && kind !in setOf("SCORED_EVALUATION", "SESSION_COACHING", "UNKNOWN") ||
            policy != null && (policy.isBlank() || policy.length > 100)
        ) invalid()
        val scope = listOf(userId.toString(), selectedSource, language, from.toString(), to.toString(), kind, policy)
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(JsonArray(scope.map { it?.let(::JsonPrimitive) ?: JsonNull }).toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val after = cursor?.let { decodeCursor(it, fingerprint) }

        // 한 읽기 트랜잭션 안에서 원본 행을 조회한다. 사용자 설정/모델/worker는 호출하지 않는다.
        val rows = work.read {
            requireActiveIfPresent(userId)
            records.learningEvidence(
                LearningEvidenceFilter(userId, selectedSource, language, from, to, kind, policy, after, limit + 1),
            )
        }
        val page = rows.take(limit)
        return buildJsonObject {
            put("items", JsonArray(page.map(::view)))
            put("nextCursor", if (rows.size > limit) JsonPrimitive(encodeCursor(page.last(), fingerprint)) else JsonNull)
        }
    }

    private fun view(row: LearningEvidenceRecord): JsonObject = buildJsonObject {
        val limitations = buildList {
            if (row.learningLanguage == null || row.originLanguage == null) add("LANGUAGE_UNKNOWN")
            if (row.policyVersion == null) add("POLICY_UNKNOWN")
            if (row.resultKind == "UNKNOWN") add("RESULT_KIND_UNKNOWN")
            if (row.resultStatus == null) add("RESULT_STATUS_UNKNOWN")
        }
        val publicId = if (row.source == "LEVEL_TEST") row.id else LearningPublicId.encode(row.id)
        put("activityId", "${row.source}:$publicId")
        put("source", row.source)
        put("learningDate", row.learningDate.toString())
        put("learningLanguage", row.learningLanguage?.let(::JsonPrimitive) ?: JsonNull)
        put("originLanguage", row.originLanguage?.let(::JsonPrimitive) ?: JsonNull)
        put("title", row.title)
        put("resultKind", row.resultKind)
        put("policyVersion", row.policyVersion?.let(::JsonPrimitive) ?: JsonNull)
        put("status", row.status)
        put("resultStatus", row.resultStatus?.let(::JsonPrimitive) ?: JsonNull)
        put("availability", if (limitations.isEmpty()) "AVAILABLE" else "LIMITED")
        put("limitations", JsonArray(limitations.map(::JsonPrimitive)))
        // 연결은 기존 상세 조회에서 현재 소유권·삭제·복습 가능 상태를 다시 확인한다.
        put("detailAvailable", true)
    }

    private fun encodeCursor(row: LearningEvidenceRecord, scope: String): String = Base64.getUrlEncoder()
        .withoutPadding().encodeToString("${row.learningDate}|${row.source}|${row.id}|$scope".toByteArray())

    private fun decodeCursor(raw: String, scope: String): LearningEvidenceCursor {
        if (raw.length > 256) invalid()
        return try {
            val parts = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8).split('|')
            if (parts.size != 4 || parts[3] != scope || parts[1] !in HistoryProjection.sources) invalid()
            val id = parts[2].toLong()
            if (id <= 0) invalid()
            LearningEvidenceCursor(LocalDate.parse(parts[0]), parts[1], id)
        } catch (_: IllegalArgumentException) {
            invalid()
        } catch (_: java.time.DateTimeException) {
            invalid()
        }
    }

    private fun invalid(): Nothing =
        throw LearningBusinessException("INVALID_REQUEST", "학습 근거 조회 조건을 확인해 주세요.")
}
