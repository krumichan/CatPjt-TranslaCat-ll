package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.exception.levelInvalid
import jp.co.translacat.languagelearning.features.leveltest.domain.exception.levelNotFound
import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelTestRules
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelSelfSelectionPolicy
import jp.co.translacat.languagelearning.features.leveltest.domain.repository.LevelTestRepository
import jp.co.translacat.languagelearning.features.settings.domain.model.ConfiguredLanguagePair
import java.time.Duration
import java.time.LocalDateTime
import java.util.*

internal data class LevelStatus(
    val baseline: LevelBaseline?, val initialCompleted: Boolean, val recheckRecommended: Boolean,
    val active: LevelSession?, val selfSelectionAvailable: Boolean,
)

internal data class LevelItemHistory(
    val item: LevelItem, val response: LevelSubmission?, val evaluation: LevelEvaluationData?,
)

internal data class LevelHistoryDetail(val session: LevelSession, val items: List<LevelItemHistory>)

internal class LevelSessionService(
    private val work: LevelTestUnitOfWork, private val context: LevelTestContextProvider,
) {
    /** 실측 테스트만 시작하며 직접 선택은 전용 초기화 요청으로 분리한다. */
    suspend fun start(userId: Long, type: LevelTestSessionType?, idempotencyKey: String?): LevelSession {
        if (type == LevelTestSessionType.SELF_SELECTED) levelInvalid("직접 선택 전용 요청을 사용해 주세요.")
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
                if (it.sessionType == LevelTestSessionType.SELF_SELECTED) {
                    levelInvalid("실측 테스트에는 새로운 idempotencyKey가 필요합니다.")
                }
                return@write it
            }
            val allSessions = records.sessions(userId)
            val sessions = allSessions.filter { it.matches(policy) }
            sessions.firstOrNull { it.status in activeStatuses }?.let { return@write recover(records, it, nowUtc) }
            val requested = type ?: LevelTestSessionType.INITIAL
            val initialDone =
                sessions.any { it.isInitialPlacement() }
            if (requested == LevelTestSessionType.INITIAL && initialDone) levelInvalid("최초 레벨 테스트가 이미 완료되었습니다.")
            if (requested == LevelTestSessionType.RECHECK && !initialDone) levelInvalid("최초 레벨 테스트 완료 후 재측정할 수 있습니다.")
            val range = LevelTestRules.dayRange(nowUtc, policy.timezone)
            if (allSessions.any {
                it.sessionType != LevelTestSessionType.SELF_SELECTED &&
                    it.status == LevelTestSessionStatus.COMPLETED && it.completedAt != null &&
                    it.completedAt >= range.first && it.completedAt < range.second
            }) {
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

    /** 현재 언어쌍의 최초 기준점을 초기화하고, 답변이 없는 최초 테스트는 같은 트랜잭션에서 종료한다. */
    suspend fun selfSelect(userId: Long, band: String?, idempotencyKey: String?): LevelSession {
        val selected = LevelSelfSelectionPolicy.selection(band)
        val key = LevelSelfSelectionPolicy.requireKey(idempotencyKey)
        val policy = context.current(userId)
        if (policy.originLanguage.isNullOrBlank() || policy.learningLanguage.isNullOrBlank()) {
            throw LevelTestException(
                "LANGUAGE_LEARNING_SETTING_NOT_CONFIGURED", 400, "모국어와 학습 언어를 먼저 설정해 주세요.",
            )
        }

        return work.write(userId) {
            // 같은 요청의 재전송은 원본을 반환한다. 다른 구간·언어·실측의 키는 재사용하지 않는다.
            records.sessionByKey(userId, key)?.let { existing ->
                if (!existing.matches(policy) || existing.sessionType != LevelTestSessionType.SELF_SELECTED ||
                    existing.baseLevelScore != selected.score
                ) levelInvalid("다른 초기 설정에는 새로운 idempotencyKey가 필요합니다.")
                return@write existing
            }
            val sessions = records.sessions(userId).filter { it.matches(policy) }
            if (sessions.any { it.status == LevelTestSessionStatus.COMPLETED }) {
                throw LevelTestException("LEVEL_TEST_ALREADY_INITIALIZED", 409, "이미 설정된 레벨은 재측정으로 변경해 주세요.")
            }
            val active = sessions.filter { it.status in activeStatuses }
            if (active.any { !it.canBeAbandonedForSelection(records) }) {
                throw LevelTestException("LEVEL_TEST_IN_PROGRESS", 409, "진행 중인 레벨 테스트를 먼저 완료해 주세요.")
            }

            // 문항과 이전 요청 키는 보존하고 활성 상태만 종료한다. 답변 저장도 같은 사용자 잠금을 사용한다.
            val now = nowUtc
            active.forEach { pending ->
                records.saveSession(
                    pending.copy(
                        status = LevelTestSessionStatus.ABANDONED, lastActivityAt = now,
                        operationToken = null, operationKind = null, leaseUntil = null,
                    ),
                )
            }

            // 실측 20문항을 만들지 않는다. 직접 선택은 0문항·모든 영역 동일 점수로 저장한다.
            val date = LevelTestRules.today(now, policy.timezone)
            val session = records.saveSession(
                LevelSession(
                    uid = UUID.randomUUID().toString(), userId = userId,
                    sessionType = LevelTestSessionType.SELF_SELECTED, status = LevelTestSessionStatus.COMPLETED,
                    originLanguage = policy.originLanguage, learningLanguage = policy.learningLanguage,
                    timezone = policy.timezone, currentQuestionNumber = 0, currentComplexityBand = selected.band,
                    baseLevelScore = selected.score, proficiencyBand = LevelTestRules.band(selected.score),
                    domainScores = LevelTestDomain.entries.associateWith { selected.score },
                    startedAt = now, lastActivityAt = now, completedAt = now, completedDate = date,
                    idempotencyKey = key,
                ),
            )
            records.saveBaseline(
                LevelBaseline(userId, session.id, session.uid, session.sessionType, selected.score,
                    checkNotNull(session.proficiencyBand), date, now, now),
            )
            session
        }
    }

    /** 현재 언어의 기준점, 진행 상태와 답변 저장 여부에 따른 직접 선택 가능 상태를 반환한다. */
    suspend fun status(userId: Long): LevelStatus {
        val policy = context.current(userId)
        return work.write(userId) {
            // 사용자 단일 baseline 행이 다른 언어로 갱신되어도 세션 원본으로 현재 언어의 기준을 복원한다.
            val all = records.sessions(userId).filter { it.matches(policy) }
            val baseline = latestBaseline(all)
            val active = all.filter { it.status in activeStatuses }
                .map { recover(records, it, nowUtc) }
            LevelStatus(
                baseline,
                all.any { it.isInitialPlacement() },
                baseline != null && Duration.between(baseline.completedAt, nowUtc).toDays() >= 30,
                active.firstOrNull(),
                selfSelectionAvailable = !policy.originLanguage.isNullOrBlank() &&
                    !policy.learningLanguage.isNullOrBlank() &&
                    all.none { it.status == LevelTestSessionStatus.COMPLETED } &&
                    active.all { it.canBeAbandonedForSelection(records) },
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

        // 문항 생성은 답변이 아니다. 평가 실패로 첫 문항에 남은 답변도 제출로 판단한다.
        private fun LevelSession.canBeAbandonedForSelection(records: LevelTestRepository): Boolean =
            sessionType == LevelTestSessionType.INITIAL &&
                status == LevelTestSessionStatus.IN_PROGRESS &&
                currentQuestionNumber == 1 &&
                records.itemAt(id, currentQuestionNumber)?.let { records.submission(it.id) == null } != false

        // 직접 선택도 학습 시작 기준점이지만 실측 응시 이력과는 유형으로 구분한다.
        private fun LevelSession.isInitialPlacement(): Boolean = status == LevelTestSessionStatus.COMPLETED &&
            sessionType in setOf(LevelTestSessionType.INITIAL, LevelTestSessionType.SELF_SELECTED)

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
