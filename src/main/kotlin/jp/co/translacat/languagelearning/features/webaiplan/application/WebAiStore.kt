package jp.co.translacat.languagelearning.features.webaiplan.application

import jp.co.translacat.languagelearning.features.webaiplan.domain.model.*
import java.time.Instant

internal interface WebAiResultReader {
    suspend fun results(userId: Long): List<WebAiResult>
    suspend fun result(userId: Long, resultId: String): WebAiResult?
}

internal data class WebAiStoredResult(
    val result: WebAiResult,
    val submissionId: String,
    val requestHash: String,
    val leaseId: String?,
    val leaseUntil: Instant?,
    val attempts: Int = 1,
)

internal data class WebAiCapability(val userId: Long, val planId: String)

/** 사용자 행 잠금이 계획·실행·결과·성장 반영의 동시성 경계다. 외부 호출은 이 블록 밖에서만 한다. */
internal interface WebAiTransaction {
    fun plans(): List<WebAiPlan>
    fun plan(id: String): WebAiPlan?
    fun planIdExists(id: String): Boolean = plan(id) != null
    fun savePlan(value: WebAiPlan)
    fun deletePlan(id: String)
    fun rotateToken(planId: String, tokenHash: String?)
    fun tokenMatches(planId: String, tokenHash: String): Boolean
    fun requireLanguages(originLanguage: String, learningLanguage: String)
    fun verificationBusy(now: Instant, excludeResultId: String? = null): Boolean
    fun run(id: String): WebAiRun?
    fun runByKey(planId: String, executionKey: String): WebAiRun?
    fun runs(limit: Int): List<WebAiRun>
    fun saveRun(value: WebAiRun)
    fun result(id: String): WebAiStoredResult?
    fun resultBySubmission(runId: String, submissionId: String): WebAiStoredResult?
    fun resultForItem(runId: String, itemId: String): String?
    fun reserveItems(runId: String, resultId: String, itemIds: List<String>)
    fun saveResult(value: WebAiStoredResult)
    fun applyGrowth(run: WebAiRun, resultId: String, item: WebAiVerifiedItem, now: Instant)
}

internal interface WebAiStore : WebAiResultReader {
    suspend fun <T> write(userId: Long, block: WebAiTransaction.() -> T): T
    suspend fun capability(tokenHash: String): WebAiCapability?
    suspend fun recentResults(userId: Long, limit: Int): List<WebAiResult> = results(userId).take(limit)
    suspend fun pendingCandidates(now: Instant, limit: Int): List<Pair<Long, String>> = emptyList()
}
