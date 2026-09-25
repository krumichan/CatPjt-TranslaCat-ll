package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.exception.levelInvalid
import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelQuestionPolicy
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelTestRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.*

/** AI 호출 중에는 JDBC 트랜잭션을 유지하지 않는다. 후보 lease의 token이 현재 소유자인 경우에만 저장한다. */
internal class LevelQuestionService(
    private val work: LevelTestUnitOfWork,
    private val context: LevelTestContextProvider,
    private val ai: LevelTestAi,
    private val audio: LevelAudioService,
    private val leaseSeconds: Long = 210,
    private val prefetchEnabled: Boolean = true,
) {
    private data class Claim(val context: LevelGenerationContext, val candidate: LevelCandidate)

    suspend fun current(userId: Long, sessionId: Long): LevelItem {
        repeat(2) {
            val state = work.write(userId) {
                val session =
                    LevelSessionService.recover(records, LevelSessionService.owned(records, userId, sessionId), nowUtc)
                if (session.status !in LevelSessionService.activeStatuses) levelInvalid("진행 중인 레벨 테스트가 아닙니다.")
                session to records.itemAt(session.id, session.currentQuestionNumber)
            }
            val session = state.first
            state.second?.let { item ->
                if (healthy(item.data, session, item.questionNumber, item.data.complexityBand, emptyList())) {
                    schedulePrefetch(session, item)
                    return item
                }
                work.write(userId) {
                    val current = records.item(item.id) ?: return@write
                    if (current.status != LevelTestItemStatus.READY || records.submission(current.id) != null) {
                        levelInvalid("이미 답변한 문항은 자동 교체할 수 없습니다.")
                    }
                    current.poolQuestionId?.let { id ->
                        records.pool(id)
                            ?.let { records.savePool(it.copy(active = false, quarantineReason = "CONTENT_INVALID")) }
                    }
                    records.deleteUnansweredItem(current.id)
                }
            }
            if (session.status == LevelTestSessionStatus.EVALUATING) levelInvalid("평가가 진행 중입니다.")
            val settings = context.current(userId)
            if (!settings.aiEvaluationEnabled) throw LevelTestException(
                "LANGUAGE_LEARNING_SETTING_INVALID", 400, "AI 평가가 비활성화되어 있습니다.",
            )
            cached(session, settings.poolTarget)?.let { return attach(session, it) }
            var claim = claim(session, session.currentQuestionNumber, session.currentComplexityBand)
            // 다른 작업자가 생성 중이면 DB 연결 없이 최대 90초 대기한다. 중복 AI 호출은 시작하지 않는다.
            if (claim == null) {
                repeat(minOf(180, (leaseSeconds * 2).toInt()).coerceAtLeast(1)) {
                    delay(500)
                    cached(session, settings.poolTarget)?.let { return attach(session, it) }
                }
                claim = claim(session, session.currentQuestionNumber, session.currentComplexityBand)
            }
            if (claim == null) levelInvalid("문항을 생성 중입니다. 잠시 후 다시 조회해 주세요.")
            val generated = generate(claim)
            return attach(session, generated)
        }
        levelInvalid("현재 문항을 다시 확인해 주세요.")
    }

    private suspend fun cached(session: LevelSession, target: Int): LevelPoolQuestion? {
        val candidates = work.write(session.userId) {
            val items = records.items(session.id)
            val recent = records.recentItems(session.userId, session.learningLanguage, nowUtc.minusDays(90))
            val excluded = (items + recent).map { it.data.diversityMetadata.contentHash }.toSet()
            val number = session.currentQuestionNumber;
            val band = session.currentComplexityBand
            val preferred =
                LevelTestRules.scenarios(
                    session.id, number, items.map { it.data.diversityMetadata.scenarioCategory },
                    recent.map { it.data.diversityMetadata.scenarioCategory },
                )
            val prepared = records.candidate(session.id, number, band)?.takeIf { it.status == "READY" }
            val ready = prepared?.poolQuestionId?.let(records::pool)
            if (prepared != null && (ready == null || !ready.active || ready.data.diversityMetadata.contentHash in excluded)) {
                records.saveCandidate(
                    prepared.copy(
                        status = "FAILED", leaseUntil = null, poolQuestionId = null, reason = "CANDIDATE_INVALID",
                    ),
                )
            }
            val pool = records.poolQuestions(session.originLanguage, session.learningLanguage).filter { it.active }
            val type = LevelTestRules.slot(number).itemType
            val bucket = pool.filter { it.data.itemType == type && it.data.complexityBand == band }
            val minimum = LevelTestRules.poolTargets(target.coerceAtLeast(1))[type to band] ?: 0
            val reusable = if (pool.size >= target && bucket.size >= minimum) bucket else emptyList()
            (listOfNotNull(ready) + reusable).distinctBy { it.id }
                .filter { it.active && it.data.diversityMetadata.contentHash !in excluded }
                .sortedByDescending { it.data.diversityMetadata.scenarioCategory in preferred }
                .take(20) to preferred
        }
        for (value in candidates.first) {
            val data = rebound(value.data, session, session.currentQuestionNumber)
            if (healthy(
                    data, session, session.currentQuestionNumber, session.currentComplexityBand, emptyList(),
                )
            ) return value
            work.write(session.userId) {
                records.savePool(value.copy(active = false, quarantineReason = "CONTENT_OR_AUDIO_INVALID"))
                records.candidate(session.id, session.currentQuestionNumber, session.currentComplexityBand)
                    ?.takeIf { it.poolQuestionId == value.id }
                    ?.let {
                        records.saveCandidate(
                            it.copy(
                                status = "FAILED", leaseUntil = null, poolQuestionId = null,
                                reason = "CANDIDATE_INVALID",
                            ),
                        )
                    }
            }
        }
        return null
    }

    private suspend fun claim(session: LevelSession, number: Int, band: Int): Claim? = work.write(session.userId) {
        val latest = records.session(session.id) ?: return@write null
        if (latest.status !in LevelSessionService.activeStatuses || latest.currentQuestionNumber > number || number > latest.currentQuestionNumber + 1) return@write null
        val old = records.candidate(session.id, number, band)
        if (old?.status == "READY") return@write null
        if (old?.status == "GENERATING" && old.leaseUntil != null && old.leaseUntil > nowUtc) return@write null
        if (old?.status == "FAILED" && old.leaseUntil != null && old.leaseUntil > nowUtc) return@write null
        val candidate = records.saveCandidate(
            (old ?: LevelCandidate(sessionId = session.id, questionNumber = number, band = band, createdAt = nowUtc))
                .copy(
                    status = "GENERATING", token = UUID.randomUUID().toString(),
                    leaseUntil = nowUtc.plusSeconds(leaseSeconds), attempt = (old?.attempt ?: 0) + 1, reason = null,
                ),
        )
        val current = records.items(session.id)
        val recent = records.recentItems(session.userId, session.learningLanguage, nowUtc.minusDays(90))
        val previous = current.mapNotNull { item ->
            records.submission(item.id)
                ?.let { records.evaluation(it.id) }?.data?.let { item to it }
        }
        Claim(
            LevelGenerationContext(
                latest, number, band, "lt:${session.uid}:q$number:b$band:${candidate.token}", previous,
                current, recent,
                LevelTestRules.scenarios(
                    session.id, number, current.map { it.data.diversityMetadata.scenarioCategory },
                    recent.map { it.data.diversityMetadata.scenarioCategory },
                ),
                nowUtc,
            ),
            candidate,
        )
    }

    private suspend fun generate(claim: Claim): LevelPoolQuestion {
        val ctx = claim.context
        var upload: LevelAudioUpload? = null
        try {
            if (LevelTestRules.requiresAudio(LevelTestRules.slot(ctx.number).itemType)) upload =
                audio.reserveReference(ctx.session.userId)
            val data = ai.generate(ctx, upload)
            LevelQuestionPolicy.validate(data, ctx.session, ctx.number, ctx.band, ctx.scenarios)
            upload?.let { audio.verify(checkNotNull(data.referenceAudio), it) }
            val pool = work.write(ctx.session.userId) {
                val candidate = records.candidate(ctx.session.id, ctx.number, ctx.band)
                if (candidate == null || candidate.token != claim.candidate.token || candidate.status != "GENERATING" || candidate.leaseUntil == null || candidate.leaseUntil <= nowUtc) {
                    levelInvalid("만료된 문항 생성 결과입니다. 다시 조회해 주세요.")
                }
                val items = records.items(ctx.session.id)
                if (items.any { it.data.diversityMetadata.contentHash == data.diversityMetadata.contentHash }) {
                    throw LevelTestException("AI_GENERATION_FAILED", 502, "동일한 내용의 문항이 생성되었습니다.")
                }
                val saved = records.savePool(
                    LevelPoolQuestion(
                        originLanguage = ctx.session.originLanguage, learningLanguage = ctx.session.learningLanguage,
                        data = data, createdAt = nowUtc,
                    ),
                )
                if (!saved.active) throw LevelTestException("AI_GENERATION_FAILED", 502, "격리된 문항을 다시 생성했습니다.")
                records.saveCandidate(
                    candidate.copy(status = "READY", token = null, leaseUntil = null, poolQuestionId = saved.id),
                )
                saved
            }
            if (upload != null && pool.data.referenceAudio?.objectKey != upload.objectKey) audio.abandon(
                upload.objectKey,
            )
            return pool
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching {
                    work.write(ctx.session.userId) {
                        val candidate = records.candidate(ctx.session.id, ctx.number, ctx.band)
                        if (candidate != null && candidate.token == claim.candidate.token) records.saveCandidate(
                            candidate.copy(
                                status = "FAILED", token = null, leaseUntil = nowUtc.plusSeconds(5),
                                reason = "GENERATION_FAILED",
                            ),
                        )
                    }
                }
                upload?.let { runCatching { audio.abandon(it.objectKey) } }
            }
            throw failure
        }
    }

    private suspend fun attach(session: LevelSession, pool: LevelPoolQuestion): LevelItem {
        val result = work.write(session.userId) {
            val latest = LevelSessionService.owned(records, session.userId, session.id)
            if (latest.status != LevelTestSessionStatus.IN_PROGRESS || latest.currentQuestionNumber != session.currentQuestionNumber || latest.currentComplexityBand != session.currentComplexityBand) {
                levelInvalid("세션의 현재 문항이 변경되었습니다.")
            }
            records.itemAt(session.id, session.currentQuestionNumber)?.let { return@write it }
            val data = rebound(pool.data, session, session.currentQuestionNumber)
            val item = records.saveItem(
                LevelItem(
                    sessionId = session.id, questionNumber = session.currentQuestionNumber, data = data,
                    poolQuestionId = pool.id, createdAt = nowUtc,
                ),
            )
            records.saveSession(latest.copy(lastActivityAt = nowUtc))
            item
        }
        schedulePrefetch(session, result)
        return result
    }

    private suspend fun schedulePrefetch(session: LevelSession, item: LevelItem) {
        if (!prefetchEnabled || item.questionNumber >= LevelTestRules.TOTAL) return
        work.write(session.userId) {
            val latest = records.session(session.id) ?: return@write
            if (latest.status !in LevelSessionService.activeStatuses) return@write
            val number = item.questionNumber + 1
            LevelTestRules.candidateBands(item.data.complexityBand, item.data.answerMode == LevelTestAnswerMode.CHOICE)
                .forEach { band ->
                    if (records.candidate(session.id, number, band) == null) records.saveCandidate(
                        LevelCandidate(
                            sessionId = session.id, questionNumber = number, band = band, createdAt = nowUtc
                        ),
                    )
                }
        }
    }

    /** 미완료 후보를 DB에서 회수한다. 프로세스 메모리의 launch만으로 작업을 기억하지 않는다. */
    suspend fun prefetchOnce() {
        if (!prefetchEnabled) return
        val queue = work.read { records.queuedCandidates(20) }
        for (candidate in queue) {
            val session = work.read { records.session(candidate.sessionId) } ?: continue
            if (session.status !in LevelSessionService.activeStatuses || candidate.questionNumber < session.currentQuestionNumber || (candidate.attempt >= 3 && (candidate.leaseUntil == null || candidate.leaseUntil <= work.read { nowUtc }))) {
                work.write(null) {
                    records.candidate(session.id, candidate.questionNumber, candidate.band)
                        ?.let { records.saveCandidate(it.copy(status = "EXPIRED", token = null, leaseUntil = null)) }
                }
                continue
            }
            val policy = context.current(session.userId)
            if (!policy.aiEvaluationEnabled) return
            val claimed = claim(session, candidate.questionNumber, candidate.band) ?: continue
            try {
                generate(claimed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* 실패는 후보에 기록하며 다음 요청에서 복구한다. */
            }
            return
        }
    }

    private suspend fun healthy(
        data: LevelQuestionData, session: LevelSession, number: Int, band: Int, scenarios: List<String>,
    ): Boolean {
        return try {
            LevelQuestionPolicy.validate(data, session, number, band, scenarios)
            !LevelTestRules.requiresAudio(data.itemType) || (data.referenceAudio != null && audio.healthy(
                data.referenceAudio.objectKey,
            ))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LevelTestException) {
            if (failure.code == "AI_SCHEMA_INVALID") false else throw failure
        }
    }

    companion object {
        fun rebound(data: LevelQuestionData, session: LevelSession, number: Int) =
            data.copy(sessionId = session.id, questionNumber = number, totalQuestions = LevelTestRules.TOTAL)
    }
}
