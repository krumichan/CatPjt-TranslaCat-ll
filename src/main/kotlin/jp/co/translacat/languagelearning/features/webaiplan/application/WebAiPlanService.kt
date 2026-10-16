package jp.co.translacat.languagelearning.features.webaiplan.application

import jp.co.translacat.languagelearning.features.webaiplan.domain.WebAiPolicy
import jp.co.translacat.languagelearning.features.webaiplan.domain.WebAiSchedulePolicy
import jp.co.translacat.languagelearning.features.webaiplan.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.time.Clock
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

internal class WebAiPlanService(
    private val store: WebAiStore,
    private val context: WebAiContextProvider,
    private val verify: suspend (WebAiVerificationInput) -> WebAiVerifiedItem,
    private val clock: Clock = Clock.systemUTC(),
    private val asynchronous: Boolean = false,
) {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val random = SecureRandom()

    suspend fun plans(user: Long): List<WebAiPlan> {
        val current = context.current(user)
        return store.write(user) { plans().map { it.inherit(current) } }
    }

    suspend fun save(user: Long, id: String?, change: WebAiPlanChange): WebAiPlan {
        WebAiPolicy.plan(change)
        val current = context.current(user)
        // 계획별 키워드를 고르더라도 공통 언어·키워드 설정을 먼저 완료해야 한다.
        current.requirePlanPrerequisites()
        val selected = if (change.keywordMode == "CUSTOM") resolveKeywords(change.keywords, current) else emptyList()
        if (change.keywordMode == "CUSTOM") requirePlanKeywords(selected)
        val key = id?.let(WebAiPolicy::id) ?: change.id?.let(WebAiPolicy::id) ?: uuid()
        return store.write(user) {
            requireLanguages(current.originLanguage, current.learningLanguage)
            val previous = plan(key)
            if (previous == null && planIdExists(key)) WebAiPolicy.conflict("사용할 수 없는 계획 식별자입니다.")
            if (id != null && previous == null) WebAiPolicy.missing()
            if (id != null && change.revision != previous?.revision) {
                WebAiPolicy.conflict("다른 화면에서 변경된 계획입니다. 새로고침 후 다시 저장해 주세요.")
            }
            val value = WebAiPlan(
                key, (previous?.revision ?: 0) + 1, change.name.trim(), current.originLanguage,
                current.learningLanguage, current.feedbackLanguage, change.timezone, change.time,
                change.recurrence, change.weekdays.sorted(), change.monthDays.sorted(), change.includeLastDay,
                change.areas, change.keywordMode, selected, change.focus, change.enabled, clock.instant().toString(),
            )
            // 생성 재시도·브라우저 초안 이관은 같은 UUID와 같은 내용이면 기존 계획을 돌려준다.
            if (id == null && previous != null) {
                val comparable = value.copy(revision = previous.revision, updatedAt = previous.updatedAt)
                if (comparable != previous.inherit(current)) WebAiPolicy.conflict("같은 식별자의 계획이 이미 저장되어 있습니다.")
                previous.inherit(current)
            } else {
                savePlan(value)
                value
            }
        }
    }

    suspend fun delete(user: Long, id: String) = store.write(user) {
        plan(WebAiPolicy.id(id)) ?: WebAiPolicy.missing()
        deletePlan(id)
    }

    suspend fun connection(user: Long, id: String): String {
        val current = context.current(user)
        current.requirePlanPrerequisites()
        val token = "tcwa_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        store.write(user) {
            requireLanguages(current.originLanguage, current.learningLanguage)
            val saved = plan(WebAiPolicy.id(id)) ?: WebAiPolicy.missing()
            if (saved.keywordMode == "CUSTOM") requirePlanKeywords(resolveKeywords(saved.keywords, current))
            rotateToken(id, WebAiPolicy.sha256(token))
        }
        return token
    }

    suspend fun revoke(user: Long, id: String) = store.write(user) {
        plan(WebAiPolicy.id(id)) ?: WebAiPolicy.missing()
        rotateToken(id, null)
    }

    suspend fun run(user: Long, id: String, executionKey: String, tokenHash: String? = null): WebAiRun {
        WebAiPolicy.id(id)
        WebAiPolicy.executionKey(executionKey)
        val current = context.current(user)
        // 설정 스냅샷의 준비 여부는 새 출제에만 적용하고 이전 답안·이력 처리는 보존한다.
        current.requirePlanPrerequisites()
        return store.write(user) {
            if (tokenHash != null && !tokenMatches(id, tokenHash)) unauthorized()
            val plan = plan(id)?.inherit(current) ?: WebAiPolicy.missing()
            if (!plan.enabled) throw WebAiException("WEB_AI_PLAN_DISABLED", "일시 정지된 학습 계획입니다.", 409)
            requireLanguages(current.originLanguage, current.learningLanguage)
            // 외부 연동은 임의 실행 키로 배정 문항 수를 늘릴 수 없다. 서버의 반복 회차가 멱등성 기준이다.
            val occurrence = if (tokenHash != null) WebAiSchedulePolicy.latestOccurrence(plan, clock.instant()) else null
            val pairKey = WebAiPolicy.sha256("${current.originLanguage}:${current.learningLanguage}").take(12)
            val actualKey = occurrence?.let { "scheduled:v${plan.revision}:$pairKey:${it.toInstant()}" } ?: executionKey
            runByKey(id, actualKey)?.also { requirePair(it, current) } ?: run {
                val resolved = if (plan.keywordMode == "DEFAULT") current.defaultKeywords else resolveKeywords(plan.keywords, current)
                requirePlanKeywords(resolved)
                val now = clock.instant()
                val empty = WebAiRun(
                    uuid(), id, actualKey, current.originLanguage, current.learningLanguage, current.feedbackLanguage,
                    plan.areas, resolved, "", now.toString(), (occurrence?.toLocalDate() ?: now.atZone(ZoneId.of(plan.timezone)).toLocalDate()).toString(),
                    plan.name, plan.revision, plan.timezone,
                )
                val generated = empty.copy(prompt = WebAiPrompt.build(empty, plan.focus))
                saveRun(generated)
                generated
            }
        }
    }

    suspend fun externalRun(token: String, executionKey: String): WebAiRun {
        val hash = tokenHash(token)
        val capability = store.capability(hash) ?: unauthorized()
        return run(capability.userId, capability.planId, executionKey, hash)
    }

    suspend fun runs(user: Long, limit: Int): List<WebAiRun> = store.write(user) { runs(limit.coerceIn(1, 100)) }
    suspend fun runById(user: Long, id: String): WebAiRun = store.write(user) { run(WebAiPolicy.id(id)) ?: WebAiPolicy.missing() }
    suspend fun results(user: Long, limit: Int): List<WebAiResult> = store.recentResults(user, limit.coerceIn(1, 100))
    suspend fun result(user: Long, id: String): WebAiResult = store.result(user, WebAiPolicy.id(id)) ?: WebAiPolicy.missing()

    suspend fun externalSubmit(token: String, submission: WebAiResultSubmission): WebAiResult {
        val hash = tokenHash(token)
        val capability = store.capability(hash) ?: unauthorized()
        return submit(capability.userId, submission, capability.planId, hash)
    }

    suspend fun submit(
        user: Long, submission: WebAiResultSubmission, capabilityPlan: String? = null, tokenHash: String? = null,
    ): WebAiResult {
        val current = context.current(user)
        val hash = WebAiPolicy.sha256(json.encodeToString(submission))
        val lease = uuid()
        val stored = store.write(user) {
            val run = run(WebAiPolicy.id(submission.runId)) ?: WebAiPolicy.missing()
            if (capabilityPlan != null && (run.planId != capabilityPlan || !tokenMatches(capabilityPlan, tokenHash!!))) unauthorized()
            if (capabilityPlan != null && plan(run.planId)?.enabled != true) {
                throw WebAiException("WEB_AI_PLAN_DISABLED", "일시 정지된 학습 계획입니다.", 409)
            }
            requirePair(run, current)
            requireLanguages(run.originLanguage, run.learningLanguage)
            WebAiPolicy.submission(submission, run)
            val previous = resultBySubmission(run.id, submission.submissionId)
            if (previous != null) {
                if (previous.requestHash != hash) WebAiPolicy.conflict("같은 제출 식별자에는 동일한 답안만 재전송할 수 있습니다.")
                return@write previous
            }
            if (verificationBusy(clock.instant())) {
                throw WebAiException("WEB_AI_VERIFICATION_BUSY", "다른 답안을 검증 중입니다. 완료 후 이어서 제출해 주세요.", 429)
            }
            if (submission.items.any { resultForItem(run.id, it.itemId) != null }) {
                WebAiPolicy.conflict("이미 제출한 문항입니다. 기존 결과를 확인하거나 검증 재시도를 사용해 주세요.")
            }

            // 답안·원문 피드백·문항 예약을 먼저 commit하고 평가 호출은 잠금 밖에서 수행한다.
            val now = clock.instant()
            val result = WebAiResult(
                uuid(), run.id, user, if (asynchronous) "PENDING" else "VERIFYING", submission.originalFeedbackMarkdown,
                submission.items.map { WebAiVerifiedItem(it, "PENDING") }, now.toString(), now.toString(),
                run.originLanguage, run.learningLanguage, run.feedbackLanguage, run.learningDate, run.planName,
            )
            WebAiStoredResult(result, submission.submissionId, hash,
                if (asynchronous) null else lease, if (asynchronous) null else now.plusSeconds(300),
                attempts = if (asynchronous) 0 else 1).also {
                saveResult(it)
                reserveItems(run.id, result.id, submission.items.map(WebAiSubmittedItem::itemId))
            }
        }
        if (stored.leaseId != lease) return stored.result
        return process(user, stored.result.id, lease)
    }

    suspend fun retry(user: Long, id: String): WebAiResult {
        if (!asynchronous) return performRetry(user, id)
        val current = context.current(user)
        if (!current.evaluationEnabled) {
            throw WebAiException("WEB_AI_EVALUATION_DISABLED", "현재 AI 평가가 일시 중지되어 있습니다. 원문 피드백은 보존됩니다.", 409)
        }
        return store.write(user) {
            val stored = result(WebAiPolicy.id(id)) ?: WebAiPolicy.missing()
            val run = run(stored.result.runId) ?: WebAiPolicy.missing()
            requirePair(run, current)
            requireLanguages(run.originLanguage, run.learningLanguage)
            if (stored.result.items.none { it.status in setOf("PENDING", "FAILED") }) return@write stored.result
            if (stored.leaseId != null && stored.leaseUntil?.isAfter(clock.instant()) == true) {
                WebAiPolicy.conflict("이미 검증 중입니다. 잠시 후 결과를 확인해 주세요.")
            }
            if (stored.attempts >= 3) {
                throw WebAiException("WEB_AI_RETRY_LIMIT", "검증 재시도 횟수를 초과했습니다. 기존 답안과 피드백은 보존됩니다.", 409)
            }
            stored.result.copy(status = "PENDING", updatedAt = clock.instant().toString()).also {
                saveResult(stored.copy(result = it, leaseId = null, leaseUntil = null))
            }
        }
    }

    suspend fun processQueued(user: Long, id: String): WebAiResult {
        return try {
            performRetry(user, id)
        } catch (failure: WebAiException) {
            if (failure.code !in setOf("WEB_AI_LANGUAGE_CHANGED", "WEB_AI_SETTINGS_REQUIRED", "WEB_AI_RETRY_LIMIT")) throw failure
            // 대기 중 언어가 바뀐 작업은 계속 폴링하지 않고 원문을 남긴 채 종결한다.
            store.write(user) {
                val stored = result(id) ?: WebAiPolicy.missing()
                val items = stored.result.items.map {
                    if (it.status in setOf("PENDING", "FAILED")) it.copy(
                        status = if (failure.code == "WEB_AI_RETRY_LIMIT") "FAILED" else "REJECTED", reason = failure.code,
                    ) else it
                }
                val rejected = stored.result.copy(status = finalStatus(items), updatedAt = clock.instant().toString(), items = items)
                saveResult(stored.copy(result = rejected, leaseId = null, leaseUntil = null))
                rejected
            }
        }
    }

    private suspend fun performRetry(user: Long, id: String): WebAiResult {
        val current = context.current(user)
        val lease = uuid()
        val claimed = store.write(user) {
            val stored = result(WebAiPolicy.id(id)) ?: WebAiPolicy.missing()
            val run = run(stored.result.runId) ?: WebAiPolicy.missing()
            requirePair(run, current)
            requireLanguages(run.originLanguage, run.learningLanguage)
            if (stored.result.items.none { it.status in setOf("FAILED", "PENDING") }) {
                if (stored.result.status !in setOf("PENDING", "VERIFYING")) return@write stored
                // 마지막 문항 commit 뒤 프로세스가 중단된 경우에는 AI를 부르지 않고 집계 상태만 완성한다.
                return@write stored.copy(
                    result = stored.result.copy(status = finalStatus(stored.result.items), updatedAt = clock.instant().toString()),
                    leaseId = null, leaseUntil = null,
                ).also(::saveResult)
            }
            if (stored.leaseUntil?.isAfter(clock.instant()) == true && stored.leaseId != null) {
                WebAiPolicy.conflict("이미 검증 중입니다. 잠시 후 결과를 확인해 주세요.")
            }
            if (stored.attempts >= 3) {
                throw WebAiException("WEB_AI_RETRY_LIMIT", "검증 재시도 횟수를 초과했습니다. 기존 답안과 피드백은 보존됩니다.", 409)
            }
            if (verificationBusy(clock.instant(), id)) {
                throw WebAiException("WEB_AI_VERIFICATION_BUSY", "다른 답안을 검증 중입니다. 완료 후 다시 시도해 주세요.", 429)
            }
            stored.copy(
                result = stored.result.copy(status = "VERIFYING", updatedAt = clock.instant().toString()),
                leaseId = lease, leaseUntil = clock.instant().plusSeconds(300),
                attempts = stored.attempts + 1,
            ).also(::saveResult)
        }
        if (claimed.leaseId != lease) return claimed.result
        return process(user, id, lease)
    }

    private suspend fun process(user: Long, id: String, lease: String): WebAiResult {
        try {
            withTimeout(240_000) {
                val initial = result(user, id)
                for (pending in initial.items.filter { it.status in setOf("PENDING", "FAILED") }) {
                    val before = context.current(user)
                    if (initial.originLanguage != before.originLanguage || initial.learningLanguage != before.learningLanguage) {
                        throw WebAiException("WEB_AI_LANGUAGE_CHANGED", "학습 언어가 변경되었습니다.", 409)
                    }
                    val verified = try {
                        if (!before.evaluationEnabled) WebAiVerifiedItem(pending.item, "FAILED", reason = "AI_EVALUATION_DISABLED") else
                            verify(WebAiVerificationInput("web-ai:$id:${WebAiPolicy.sha256(pending.item.itemId).take(16)}", initial.originLanguage,
                                initial.learningLanguage, initial.feedbackLanguage, pending.item))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        WebAiVerifiedItem(pending.item, "FAILED", reason = "VERIFICATION_UNAVAILABLE")
                    }

                    // 평가 중 설정 변경·다른 작업자의 lease 획득이 있었으면 늦은 결과를 점수로 반영하지 않는다.
                    val current = context.current(user)
                    store.write(user) {
                        val stored = result(id) ?: WebAiPolicy.missing()
                        if (stored.leaseId != lease) WebAiPolicy.conflict("검증 작업의 소유권이 변경되었습니다.")
                        val run = run(stored.result.runId) ?: WebAiPolicy.missing()
                        requirePair(run, current)
                        requireLanguages(run.originLanguage, run.learningLanguage)
                        if (verified.item != pending.item || verified.status !in setOf("VERIFIED", "COACHING", "REJECTED", "FAILED") ||
                            (verified.status == "VERIFIED" && (verified.officialScore == null || !verified.officialScore.isFinite() || verified.officialScore !in 0.0..100.0))
                        ) throw WebAiException("WEB_AI_VERIFICATION_INVALID", "검증 응답이 유효하지 않습니다.", 502)
                        val items = stored.result.items.map { if (it.item.itemId == pending.item.itemId) verified else it }
                        applyGrowth(run, id, verified, clock.instant())
                        saveResult(stored.copy(result = stored.result.copy(items = items, updatedAt = clock.instant().toString())))
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // 프로세스 중단/전체 deadline은 lease 만료 뒤 작업자가 복구한다. 공식 반영된 문항은 유지한다.
            throw cancelled
        } catch (failure: WebAiException) {
            finalize(user, id, lease, failure.code)
            throw failure
        }
        return finalize(user, id, lease)
    }

    private suspend fun finalize(user: Long, id: String, lease: String, rejection: String? = null): WebAiResult = store.write(user) {
        val stored = result(id) ?: WebAiPolicy.missing()
        if (stored.leaseId != lease) return@write stored.result
        val items = stored.result.items.map {
            if (it.status == "PENDING" && rejection != null) it.copy(status = "REJECTED", reason = rejection) else it
        }
        val status = finalStatus(items)
        stored.result.copy(status = status, items = items, updatedAt = clock.instant().toString()).also {
            saveResult(stored.copy(result = it, leaseId = null, leaseUntil = null))
        }
    }

    private fun finalStatus(items: List<WebAiVerifiedItem>): String = when {
        items.all { it.status == "VERIFIED" } -> "VERIFIED"
        items.all { it.status == "REJECTED" } -> "REJECTED"
        items.all { it.status in setOf("FAILED", "PENDING") } -> "FAILED"
        else -> "PARTIAL"
    }

    private fun requirePlanKeywords(keywords: List<WebAiKeyword>) {
        if (keywords.isEmpty()) {
            throw WebAiException("WEB_AI_PLAN_KEYWORDS_REQUIRED", "이 계획에서 사용할 키워드를 한 개 이상 선택해 주세요.", 409)
        }
    }

    private fun WebAiPlan.inherit(value: WebAiContext) = copy(
        originLanguage = value.originLanguage, learningLanguage = value.learningLanguage, feedbackLanguage = value.feedbackLanguage,
    )

    private fun resolveKeywords(selected: List<WebAiKeyword>, current: WebAiContext): List<WebAiKeyword> = selected.map { value ->
        current.catalog.singleOrNull { it.key == value.key }
            ?: throw WebAiException("WEB_AI_KEYWORD_UNAVAILABLE", "삭제되거나 비활성화된 키워드가 있습니다. 계획의 키워드를 다시 선택해 주세요.", 409)
    }

    private fun requirePair(run: WebAiRun, current: WebAiContext) {
        if (run.originLanguage != current.originLanguage || run.learningLanguage != current.learningLanguage) {
            throw WebAiException("WEB_AI_LANGUAGE_CHANGED", "실행 이후 기본 학습 언어가 변경되었습니다. 새 실행을 만들어 주세요.", 409)
        }
    }

    private fun tokenHash(token: String): String {
        if (!Regex("tcwa_[A-Za-z0-9_-]{43}").matches(token)) unauthorized()
        return WebAiPolicy.sha256(token)
    }

    private fun unauthorized(): Nothing = throw WebAiException("WEB_AI_TOKEN_INVALID", "연동 키가 없거나 폐기되었습니다.", 401)
    private fun uuid(): String = UUID.randomUUID().toString()
}
