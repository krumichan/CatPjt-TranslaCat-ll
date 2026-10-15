package jp.co.translacat.languagelearning.features.webaiplan.support

import jp.co.translacat.languagelearning.features.webaiplan.application.*
import jp.co.translacat.languagelearning.features.webaiplan.domain.model.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** 합성 데이터 전용 저장소. 실제 DB나 Provider 없이 잠금·소유권·rollback 업무 경로를 검증한다. */
internal class WebAiMemoryStore : WebAiStore {
    var originLanguage = "ko"
    var learningLanguage = "ja"
    val languagePairs = mutableMapOf<Long, Pair<String, String>>()
    val writes = mutableListOf<Long>()
    val growthApplications = mutableListOf<GrowthApplication>()
    private val mutex = Mutex()
    private var state = State()

    data class GrowthApplication(
        val userId: Long, val resultId: String, val itemId: String, val verified: WebAiVerifiedItem,
    )

    override suspend fun <T> write(userId: Long, block: WebAiTransaction.() -> T): T = mutex.withLock {
        require(userId > 0)
        writes += userId
        val before = state.copyForRollback()
        val growthCount = growthApplications.size

        // 실패한 transaction의 문항 예약과 점수 반영도 함께 되돌린다.
        try {
            block(Transaction(userId))
        } catch (failure: Throwable) {
            state = before
            while (growthApplications.size > growthCount) growthApplications.removeLast()
            throw failure
        }
    }

    override suspend fun capability(tokenHash: String): WebAiCapability? = mutex.withLock {
        state.tokens.entries.singleOrNull { it.value == tokenHash }?.key?.let { id ->
            state.plans[id]?.let { WebAiCapability(it.first, id) }
        }
    }

    override suspend fun results(userId: Long): List<WebAiResult> = mutex.withLock {
        state.results.values.map(WebAiStoredResult::result).filter { it.userId == userId }
            .sortedByDescending { it.createdAt }
    }

    override suspend fun result(userId: Long, resultId: String): WebAiResult? = mutex.withLock {
        state.results[resultId]?.result?.takeIf { it.userId == userId }
    }

    private inner class Transaction(private val user: Long) : WebAiTransaction {
        override fun plans() = state.plans.values.filter { it.first == user }.map { it.second }
            .sortedByDescending { it.updatedAt }

        override fun plan(id: String) = state.plans[id]?.takeIf { it.first == user }?.second
        override fun planIdExists(id: String) = state.plans.containsKey(id)

        override fun savePlan(value: WebAiPlan) {
            val previous = state.plans[value.id]
            check(previous == null || previous.first == user)
            state.plans[value.id] = user to value
        }

        override fun deletePlan(id: String) {
            check(plan(id) != null)
            state.plans.remove(id)
            state.tokens.remove(id)
        }

        override fun rotateToken(planId: String, tokenHash: String?) {
            check(plan(planId) != null)
            if (tokenHash == null) state.tokens.remove(planId) else state.tokens[planId] = tokenHash
        }

        override fun tokenMatches(planId: String, tokenHash: String) =
            plan(planId) != null && state.tokens[planId] == tokenHash

        override fun requireLanguages(originLanguage: String, learningLanguage: String) {
            val current = languagePairs[user]
                ?: (this@WebAiMemoryStore.originLanguage to this@WebAiMemoryStore.learningLanguage)
            if (current != (originLanguage to learningLanguage)) {
                throw WebAiException("WEB_AI_LANGUAGE_CHANGED", "학습 언어가 변경되었습니다.", 409)
            }
        }

        override fun verificationBusy(now: Instant, excludeResultId: String?) = state.results.values.any {
            it.result.userId == user && it.result.id != excludeResultId && it.leaseId != null &&
                it.leaseUntil?.isAfter(now) == true
        }

        override fun run(id: String) = state.runs[id]?.takeIf { it.first == user }?.second

        override fun runByKey(planId: String, executionKey: String) = state.runs.values
            .filter { it.first == user }.map { it.second }
            .singleOrNull { it.planId == planId && it.executionKey == executionKey }

        override fun runs(limit: Int) = state.runs.values.filter { it.first == user }.map { it.second }
            .sortedByDescending { it.createdAt }.take(limit)

        override fun saveRun(value: WebAiRun) {
            check(plan(value.planId) != null)
            check(runByKey(value.planId, value.executionKey) == null)
            state.runs[value.id] = user to value
        }

        override fun result(id: String) = state.results[id]?.takeIf { it.result.userId == user }

        override fun resultBySubmission(runId: String, submissionId: String) = state.results.values
            .singleOrNull { it.result.userId == user && it.result.runId == runId && it.submissionId == submissionId }

        override fun resultForItem(runId: String, itemId: String): String? {
            if (run(runId) == null) return null
            return state.reserved[runId to itemId]
        }

        override fun reserveItems(runId: String, resultId: String, itemIds: List<String>) {
            check(run(runId) != null)
            for (id in itemIds) {
                check(state.reserved.putIfAbsent(runId to id, resultId) == null)
            }
        }

        override fun saveResult(value: WebAiStoredResult) {
            check(value.result.userId == user && run(value.result.runId) != null)
            val previous = state.results[value.result.id]
            check(previous == null || previous.result.userId == user)
            state.results[value.result.id] = value
        }

        override fun applyGrowth(run: WebAiRun, resultId: String, item: WebAiVerifiedItem, now: Instant) {
            if (item.status != "VERIFIED" || item.officialScore == null) return
            check(growthApplications.none {
                it.userId == user && it.resultId == resultId && it.itemId == item.item.itemId
            })
            growthApplications += GrowthApplication(user, resultId, item.item.itemId, item)
        }
    }

    private data class State(
        val plans: MutableMap<String, Pair<Long, WebAiPlan>> = linkedMapOf(),
        val tokens: MutableMap<String, String> = linkedMapOf(),
        val runs: MutableMap<String, Pair<Long, WebAiRun>> = linkedMapOf(),
        val results: MutableMap<String, WebAiStoredResult> = linkedMapOf(),
        val reserved: MutableMap<Pair<String, String>, String> = linkedMapOf(),
    ) {
        fun copyForRollback() = State(plans.toMutableMap(), tokens.toMutableMap(), runs.toMutableMap(),
            results.toMutableMap(), reserved.toMutableMap())
    }
}
