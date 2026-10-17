package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.exception.levelInvalid
import jp.co.translacat.languagelearning.features.leveltest.domain.exception.levelNotFound
import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelTestRules
import jp.co.translacat.languagelearning.features.leveltest.domain.repository.LevelTestRepository
import jp.co.translacat.languagelearning.features.settings.domain.model.ConfiguredLanguagePair
import java.time.Duration
import java.time.LocalDateTime
import java.util.*

internal data class LevelStatus(
    val baseline: LevelBaseline?, val initialCompleted: Boolean, val recheckRecommended: Boolean,
    val active: LevelSession?,
)

internal data class LevelItemHistory(
    val item: LevelItem, val response: LevelSubmission?, val evaluation: LevelEvaluationData?,
)

internal data class LevelHistoryDetail(val session: LevelSession, val items: List<LevelItemHistory>)

internal class LevelSessionService(
    private val work: LevelTestUnitOfWork, private val context: LevelTestContextProvider,
) {
    suspend fun start(userId: Long, type: LevelTestSessionType?, idempotencyKey: String?): LevelSession {
        val policy = context.current(userId)
        if (!policy.aiEvaluationEnabled) throw LevelTestException(
            "LANGUAGE_LEARNING_SETTING_INVALID", 400, "AI 평가가 비활성화되어 있습니다.",
        )
        val key = LevelTestRules.requireKey(idempotencyKey)
        return work.write(userId) {
            if (policy.originLanguage.isNullOrBlank() || policy.learningLanguage.isNullOrBlank()) {
                throw LevelTestException("LANGUAGE_LEARNING_SETTING_NOT_CONFIGURED", 400, "모국어와 학습 언어를 먼저 설정해 주세요.")
            }
            // 다른 언어의 과거 요청이나 진행 중 테스트를 현재 언어의 시작 결과로 반환하지 않는다.
            records.sessionByKey(userId, key)?.let {
                if (!it.matches(policy)) levelInvalid("현재 언어쌍에는 새로운 idempotencyKey가 필요합니다.")
                return@write it
            }
            val allSessions = records.sessions(userId)
            val sessions = allSessions.filter { it.matches(policy) }
            sessions.firstOrNull { it.status in activeStatuses }?.let { return@write recover(records, it, nowUtc) }
            val requested = type ?: LevelTestSessionType.INITIAL
            val initialDone =
                sessions.any { it.sessionType == LevelTestSessionType.INITIAL && it.status == LevelTestSessionStatus.COMPLETED }
            if (requested == LevelTestSessionType.INITIAL && initialDone) levelInvalid("최초 레벨 테스트가 이미 완료되었습니다.")
            if (requested == LevelTestSessionType.RECHECK && !initialDone) levelInvalid("최초 레벨 테스트 완료 후 재측정할 수 있습니다.")
            val range = LevelTestRules.dayRange(nowUtc, policy.timezone)
            if (allSessions.any { it.status == LevelTestSessionStatus.COMPLETED && it.completedAt != null && it.completedAt >= range.first && it.completedAt < range.second }) {
                throw LevelTestException("LEVEL_TEST_DAILY_LIMIT_REACHED", 409, "오늘의 공식 레벨 테스트가 이미 완료되었습니다.")
            }
            records.saveSession(
                LevelSession(
                    uid = UUID.randomUUID().toString(), userId = userId, sessionType = requested,
                    originLanguage = policy.originLanguage, learningLanguage = policy.learningLanguage,
                    timezone = policy.timezone,
                    currentComplexityBand = LevelTestRules.initialBand(
                        latestBaseline(sessions)?.score, requested == LevelTestSessionType.RECHECK,
                    ),
                    startedAt = nowUtc, lastActivityAt = nowUtc, idempotencyKey = key,
                ),
            )
        }
    }

    suspend fun status(userId: Long): LevelStatus {
        val policy = context.current(userId)
        return work.write(userId) {
            // 사용자 단일 baseline 행이 다른 언어로 갱신되어도 세션 원본으로 현재 언어의 기준을 복원한다.
            val all = records.sessions(userId).filter { it.matches(policy) }
            val baseline = latestBaseline(all)
            LevelStatus(
                baseline,
                all.any { it.sessionType == LevelTestSessionType.INITIAL && it.status == LevelTestSessionStatus.COMPLETED },
                baseline != null && Duration.between(baseline.completedAt, nowUtc).toDays() >= 30,
                all.firstOrNull { it.status in activeStatuses }?.let { recover(records, it, nowUtc) },
            )
        }
    }

    suspend fun session(userId: Long, id: Long): LevelSession =
        work.write(userId) { recover(records, owned(records, userId, id), nowUtc) }

    suspend fun baseline(userId: Long): LevelBaseline? {
        val policy = context.current(userId)
        return work.write(userId) { latestBaseline(records.sessions(userId).filter { it.matches(policy) }) }
    }

    // 과거 원문 이력은 언어를 바꿔도 보존한다. 현재 레벨은 status/baseline 경계에서만 제공한다.
    suspend fun history(userId: Long): List<LevelSession> =
        work.write(userId) { records.sessions(userId).filter { it.status == LevelTestSessionStatus.COMPLETED } }

    suspend fun detail(userId: Long, sessionId: Long): LevelHistoryDetail = work.write(userId) {
        val session = owned(records, userId, sessionId)
        if (session.status != LevelTestSessionStatus.COMPLETED) levelNotFound()
        LevelHistoryDetail(
            session,
            records.items(session.id).map { item ->
                val response = records.submission(item.id)
                LevelItemHistory(item, response, response?.let { records.evaluation(it.id)?.data })
            },
        )
    }

    companion object {
        val activeStatuses = setOf(LevelTestSessionStatus.IN_PROGRESS, LevelTestSessionStatus.EVALUATING)

        private fun LevelSession.matches(policy: LevelTestContext): Boolean =
            ConfiguredLanguagePair(originLanguage, learningLanguage).matches(policy.originLanguage, policy.learningLanguage)

        private fun latestBaseline(sessions: List<LevelSession>): LevelBaseline? = sessions
            .filter { it.status == LevelTestSessionStatus.COMPLETED }
            .sortedByDescending { it.completedAt }
            .firstNotNullOfOrNull { session ->
                val score = session.baseLevelScore
                val band = session.proficiencyBand
                val date = session.completedDate
                val completed = session.completedAt
                if (score == null || band == null || date == null || completed == null) null
                else LevelBaseline(session.userId, session.id, session.uid, session.sessionType,
                    score, band, date, session.startedAt, completed)
            }

        fun owned(records: LevelTestRepository, userId: Long, id: Long): LevelSession =
            records.session(id)?.takeIf { it.userId == userId } ?: levelNotFound()

        /** 프로세스 재시작 후에도 만료된 lease는 다음 요청에서 회수한다. 늦은 결과는 token 비교로 차단한다. */
        fun recover(records: LevelTestRepository, value: LevelSession, now: LocalDateTime): LevelSession {
            if (value.status !in activeStatuses || value.operationToken == null || value.leaseUntil == null || value.leaseUntil > now) return value
            if (value.operationKind == "EVALUATE") {
                records.itemAt(value.id, value.currentQuestionNumber)
                    ?.let { records.saveItem(it.copy(status = LevelTestItemStatus.EVALUATION_FAILED)) }
            }
            return records.saveSession(
                value.copy(
                    status = LevelTestSessionStatus.IN_PROGRESS, operationToken = null, operationKind = null,
                    leaseUntil = null, lastActivityAt = now,
                ),
            )
        }
    }
}
