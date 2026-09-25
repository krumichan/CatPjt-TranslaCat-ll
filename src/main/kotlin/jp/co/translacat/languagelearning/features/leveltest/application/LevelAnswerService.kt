package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.exception.levelInvalid
import jp.co.translacat.languagelearning.features.leveltest.domain.exception.levelNotFound
import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelAudioPolicy
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelQuestionPolicy
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelTestRules
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.*

internal data class LevelAnswerResult(
    val session: LevelSession, val item: LevelItem, val response: LevelSubmission, val evaluation: LevelEvaluationData?,
)

internal class LevelAnswerService(
    private val work: LevelTestUnitOfWork,
    private val context: LevelTestContextProvider,
    private val ai: LevelTestAi,
    private val audio: LevelAudioService,
    private val leaseSeconds: Long = 210,
) {
    private data class Prepared(val value: LevelAnswerResult, val token: String?)

    suspend fun submitText(
        userId: Long, sessionId: Long, itemId: Long, key: String?, option: String?, keys: List<String>?, text: String?,
    ): LevelAnswerResult {
        val idempotencyKey = LevelTestRules.requireKey(key)
        val selected = option?.trim();
        val order = keys.orEmpty().map { it.trim() };
        val answer = text?.trim()
        val fingerprint = fingerprint(listOf(selected, order.joinToString("") { "${it.length}:$it" }, answer))
        val prepared = work.write(userId) {
            val (session, item) = target(records, userId, sessionId, itemId, nowUtc)
            LevelTestRules.validateText(item.data, selected, order, answer)
            val old = records.submission(itemId)
            if (old != null) return@write replayOrReject(session, item, old, idempotencyKey, fingerprint)
            answerable(session, item)
            val response = records.saveSubmission(
                LevelSubmission(
                    itemId = itemId, idempotencyKey = idempotencyKey, fingerprint = fingerprint,
                    selectedOptionKey = selected, selectedOptionKeys = order, textAnswer = answer, submittedAt = nowUtc,
                ),
            )
            claim(session, item, response)
        }
        return evaluate(prepared)
    }

    suspend fun submitAudio(
        userId: Long, sessionId: Long, itemId: Long, key: String?, durationMs: Int?, bytes: ByteArray, mime: String,
    ): LevelAnswerResult {
        val idempotencyKey = LevelTestRules.requireKey(key)
        val type = LevelAudioPolicy.validate(bytes, mime)
        val fingerprint = fingerprint(listOf(type, durationMs?.toString(), LevelTestRules.sha256(bytes)))
        val replay = work.write(userId) {
            val (session, item) = target(records, userId, sessionId, itemId, nowUtc)
            if (item.data.answerMode != LevelTestAnswerMode.AUDIO || durationMs == null || durationMs <= 0 || durationMs.toLong() > (item.data.maxAudioSeconds
                    ?: 30) * 1000L
            ) {
                throw LevelTestException("LEVEL_TEST_AUDIO_INVALID", 400, "문항의 녹음 방식과 길이를 확인해 주세요.")
            }
            val old = records.submission(itemId)
            if (old != null && old.idempotencyKey == idempotencyKey) return@write replayOrReject(
                session, item, old, idempotencyKey, fingerprint,
            )
            answerable(session, item)
            if (old != null && item.status != LevelTestItemStatus.EVALUATION_FAILED) levelInvalid("이미 녹음이 제출되었습니다.")
            null
        }
        if (replay != null) return evaluate(replay)
        val stored = audio.storeAnswer(userId, bytes, type)
        var previousAudio: String? = null
        val prepared = try {
            work.write(userId) {
                val (session, item) = target(records, userId, sessionId, itemId, nowUtc)
                val old = records.submission(itemId)
                if (old != null && old.idempotencyKey == idempotencyKey) return@write replayOrReject(
                    session, item, old, idempotencyKey, fingerprint,
                )
                answerable(session, item)
                if (old != null && item.status != LevelTestItemStatus.EVALUATION_FAILED) levelInvalid("이미 녹음이 제출되었습니다.")
                previousAudio = old?.audioKey
                // 재녹음은 새 revision이다. 이전 음성의 평가 사유를 새 시도에 재사용하지 않는다.
                if (old != null) records.clearEvaluation(old.id)
                val response = records.saveSubmission(
                    LevelSubmission(
                        id = old?.id ?: 0, itemId = item.id,
                        idempotencyKey = idempotencyKey, fingerprint = fingerprint, audioKey = stored.key,
                        audioContentType = stored.contentType,
                        audioDurationMs = durationMs, audioRetentionUntil = stored.retentionUntil, submittedAt = nowUtc,
                        revision = (old?.revision ?: 0) + 1,
                    ),
                )
                claim(session, item, response)
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { runCatching { audio.abandon(stored.key) } }
            throw failure
        }
        if (prepared.value.response.audioKey != stored.key) audio.abandon(stored.key)
        if (previousAudio != null && previousAudio != stored.key) audio.abandon(previousAudio!!)
        return evaluate(prepared)
    }

    suspend fun retry(userId: Long, sessionId: Long, itemId: Long): LevelAnswerResult {
        val prepared = work.write(userId) {
            val (session, item) = target(records, userId, sessionId, itemId, nowUtc)
            val response = records.submission(item.id) ?: levelInvalid("재평가할 답변이 없습니다.")
            if (item.status != LevelTestItemStatus.EVALUATION_FAILED) levelInvalid("재시도할 수 있는 평가 상태가 아닙니다.")
            answerable(session, item)
            if (records.evaluation(response.id)?.data?.reasonCode in LevelTestRules.rerecordReasons) levelInvalid(
                "음성을 다시 녹음해 주세요.",
            )
            if (response.manualRetryCount >= LevelTestRules.MAX_MANUAL_RETRIES) levelInvalid("수동 평가 재시도 횟수를 초과했습니다.")
            claim(
                session, item, records.saveSubmission(response.copy(manualRetryCount = response.manualRetryCount + 1)),
            )
        }
        return evaluate(prepared)
    }

    private fun LevelTestTransaction.replayOrReject(
        session: LevelSession, item: LevelItem, response: LevelSubmission, key: String, fingerprint: String,
    ): Prepared {
        if (response.idempotencyKey != key || response.fingerprint != fingerprint) levelInvalid(
            "같은 답변의 재전송 키와 내용이 일치하지 않습니다.",
        )
        if (item.status == LevelTestItemStatus.EVALUATING) levelInvalid("평가 중입니다. 완료 후 다시 조회해 주세요.")
        return Prepared(LevelAnswerResult(session, item, response, records.evaluation(response.id)?.data), null)
    }

    private fun LevelTestTransaction.claim(
        session: LevelSession, item: LevelItem, response: LevelSubmission,
    ): Prepared {
        val token = UUID.randomUUID().toString()
        val claimed = records.saveSession(
            session.copy(
                status = LevelTestSessionStatus.EVALUATING, operationToken = token,
                operationKind = "EVALUATE", leaseUntil = nowUtc.plusSeconds(leaseSeconds), lastActivityAt = nowUtc,
            ),
        )
        val evaluating = records.saveItem(item.copy(status = LevelTestItemStatus.EVALUATING))
        return Prepared(LevelAnswerResult(claimed, evaluating, response, null), token)
    }

    private suspend fun evaluate(prepared: Prepared): LevelAnswerResult {
        if (prepared.token == null) return prepared.value
        val value = prepared.value
        try {
            val policy = context.current(value.session.userId)
            if (!policy.aiEvaluationEnabled) throw LevelTestException(
                "LANGUAGE_LEARNING_SETTING_INVALID", 400, "AI 평가가 비활성화되어 있습니다.",
            )
            val result = if (value.item.data.answerMode == LevelTestAnswerMode.CHOICE) LevelTestRules.objective(
                value.item.data, value.response,
            ) else {
                val bytes = value.response.audioKey?.let { audio.load(it).bytes }
                ai.evaluate(value.session, value.item, value.response, bytes)
            }
            LevelQuestionPolicy.validateEvaluation(result, value.session, value.item)
            val completionTimezone = if (value.item.questionNumber == LevelTestRules.TOTAL) context.current(
                value.session.userId,
            ).timezone else value.session.timezone
            return work.write(value.session.userId) {
                val latest = records.session(value.session.id) ?: levelNotFound()
                val item = records.item(value.item.id) ?: levelNotFound()
                val response = records.submission(item.id) ?: levelNotFound()
                if (latest.operationToken != prepared.token || latest.operationKind != "EVALUATE" || latest.leaseUntil == null || latest.leaseUntil <= nowUtc ||
                    response.revision != value.response.revision || response.manualRetryCount != value.response.manualRetryCount
                ) {
                    levelInvalid("현재 평가 작업자가 아닌 늦은 응답은 반영하지 않습니다.")
                }
                records.saveEvaluation(LevelEvaluation(response.id, result, nowUtc))
                val updatedItem = records.saveItem(
                    item.copy(
                        status = if (result.evaluable) LevelTestItemStatus.EVALUATED else LevelTestItemStatus.EVALUATION_FAILED,
                    ),
                )
                var session = latest.copy(
                    status = LevelTestSessionStatus.IN_PROGRESS, operationToken = null, operationKind = null,
                    leaseUntil = null, lastActivityAt = nowUtc,
                )
                if (result.evaluable) {
                    val nextBand = LevelTestRules.nextBand(
                        item.data.complexityBand, result.score!!, item.data.answerMode == LevelTestAnswerMode.CHOICE,
                    )
                    if (item.questionNumber < LevelTestRules.TOTAL) session =
                        session.copy(currentQuestionNumber = item.questionNumber + 1, currentComplexityBand = nextBand)
                    else {
                        val all = records.items(session.id)
                        val evaluations = all.associate { current ->
                            current.id to (records.submission(current.id)
                                ?.let { records.evaluation(it.id)?.data } ?: levelInvalid("평가가 누락되었습니다."))
                        }
                        val scores = LevelTestRules.domainScores(all, evaluations)
                        val score = LevelTestRules.overall(scores)
                        val date = LevelTestRules.today(nowUtc, completionTimezone)
                        session = session.copy(
                            status = LevelTestSessionStatus.COMPLETED, baseLevelScore = score,
                            proficiencyBand = LevelTestRules.band(score),
                            domainScores = scores, completedAt = nowUtc, completedDate = date,
                        )
                        // 최종 점수와 학습 시작용 기준점은 같은 LL 트랜잭션에서 확정한다.
                        records.saveBaseline(
                            LevelBaseline(
                                session.userId, session.id, session.uid, session.sessionType, score,
                                LevelTestRules.band(score), date, session.startedAt, nowUtc,
                            ),
                        )
                    }
                }
                LevelAnswerResult(records.saveSession(session), updatedItem, response, result)
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching {
                    work.write(value.session.userId) {
                        val latest = records.session(value.session.id)
                        if (latest?.operationToken == prepared.token) {
                            records.item(value.item.id)
                                ?.let { records.saveItem(it.copy(status = LevelTestItemStatus.EVALUATION_FAILED)) }
                            records.saveSession(
                                latest.copy(
                                    status = LevelTestSessionStatus.IN_PROGRESS, operationToken = null,
                                    operationKind = null, leaseUntil = null, lastActivityAt = nowUtc,
                                ),
                            )
                        }
                    }
                }
            }
            throw failure
        }
    }

    companion object {
        private fun target(
            records: jp.co.translacat.languagelearning.features.leveltest.domain.repository.LevelTestRepository,
            userId: Long, sessionId: Long, itemId: Long, now: LocalDateTime,
        ): Pair<LevelSession, LevelItem> {
            val session =
                LevelSessionService.recover(records, LevelSessionService.owned(records, userId, sessionId), now)
            val item = records.item(itemId)?.takeIf { it.sessionId == sessionId } ?: levelNotFound()
            return session to item
        }

        private fun answerable(session: LevelSession, item: LevelItem) {
            if (session.status != LevelTestSessionStatus.IN_PROGRESS || session.currentQuestionNumber != item.questionNumber ||
                item.status !in setOf(LevelTestItemStatus.READY, LevelTestItemStatus.EVALUATION_FAILED)
            ) levelInvalid("현재 답변 가능한 문항이 아닙니다.")
        }

        private fun fingerprint(values: List<String?>): String = LevelTestRules.sha256(
            values.joinToString("") { if (it == null) "-1:" else "${it.length}:$it" }.toByteArray(Charsets.UTF_8),
        )
    }
}
