package jp.co.translacat.languagelearning.features.listening.application

import jp.co.translacat.languagelearning.features.listening.domain.model.*
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningAudioUploadPolicy
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningTaskSelectionPolicy
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.util.*

/** 세션 선택·점진적 문항 연결과 답변 저장의 상태/사용자 경계를 소유한다. */
internal class ListeningSessionService(
    private val work: ListeningUnitOfWork, private val settings: SettingsServiceOperations,
) {
    suspend fun create(
        userId: Long, setId: Long, selected: List<ListeningTaskType>?, idempotencyKey: String?,
    ): ListeningSessionState {
        val policy = settings.listeningPolicy()
        val revision = settings.userSnapshot(userId).result.settings.updatedAt
        val key = key(idempotencyKey)
        return work.write(userId) {
            // 요청 모드·payload를 먼저 고정하고 동일 key의 다른 payload를 거절한다.
            val set = set(userId, setId) ?: missing("LANGUAGE_LEARNING_DAILY_SET_NOT_FOUND")
            val tasks = ListeningTaskSelectionPolicy.validateForMode(
                set.learningMode,
                selected?.takeIf { it.isNotEmpty() } ?: ListeningTaskSelectionPolicy.tasksForMode(set.learningMode),
            )
            val previous = sessions(userId)
            previous.firstOrNull { it.idempotencyKey == key }?.let { existing ->
                if (existing.setId != setId || existing.selectedTaskTypes != tasks) invalid(
                    "LISTENING_IDEMPOTENCY_CONFLICT",
                )
                rememberSelection(userId, revision, tasks)
                return@write synchronize(existing, set, policy)
            }

            // 진행 중 세션은 만료 여부를 확인하고 새 공식 시도가 기존 세트 이력을 중복하지 않게 한다.
            previous.filter { it.status == "IN_PROGRESS" }.forEach { active ->
                if (expired(active, policy)) updateSession(
                    active.copy(status = "ABANDONED", lastActivityAt = nowUtc.toString()),
                )
                else invalid("LISTENING_ACTIVE_SESSION_EXISTS")
            }
            if (set.status !in setOf("READY", "PARTIAL", "COMPLETED") ||
                previous.any { it.setId == setId && it.attempts.any { attempt -> attempt.purpose == "OFFICIAL" } }
            ) invalid()
            val items = playable(set)
            if (items.isEmpty()) invalid()
            val session = ListeningSessionState(
                allocateId(), userId, setId, key, tasks,
                nowUtc.toString(), nowUtc.toString(), nowUtc.plusHours(policy.resumeHours.toLong()).toString(),
                attempts = items.map { attempt(it, tasks, "OFFICIAL", 1, "session:$key:item:${it.id}:official") },
            )
            insertSession(session)
            rememberSelection(userId, revision, tasks)
            session
        }
    }

    suspend fun get(userId: Long, id: Long): ListeningSessionState {
        val policy = settings.listeningPolicy()
        return work.write(userId) {
            val session = session(userId, id) ?: missing()
            synchronize(session, checkNotNull(set(userId, session.setId)), policy)
        }
    }

    suspend fun active(userId: Long): ListeningSessionState? {
        val policy = settings.listeningPolicy()
        return work.write(userId) {
            val active = sessions(userId).firstOrNull { it.status == "IN_PROGRESS" } ?: return@write null
            synchronize(active, checkNotNull(set(userId, active.setId)), policy).takeIf { it.status == "IN_PROGRESS" }
        }
    }

    suspend fun resume(userId: Long, id: Long): ListeningSessionState {
        val policy = settings.listeningPolicy()
        return work.write(userId) {
            val value = session(userId, id) ?: missing()
            if (value.status != "IN_PROGRESS") invalid()
            val synchronized = synchronize(value, checkNotNull(set(userId, value.setId)), policy)
            if (synchronized.status == "ABANDONED") synchronized else updateSession(touch(synchronized, policy))
        }
    }

    suspend fun answer(
        userId: Long, sessionId: Long, attemptId: Long, task: ListeningTaskType,
        answer: String, assistance: List<ListeningAssistanceUsage>,
    ): ListeningSessionState {
        if (answer.isBlank() || answer.length > 4000 || task == ListeningTaskType.REPEAT_AFTER_AUDIO) invalid()
        validateAssistance(assistance)
        val policy = settings.listeningPolicy()
        return work.write(userId) {
            val session = session(userId, sessionId) ?: missing()
            val attempt = mutable(session, attemptId, policy)
            val response = selected(attempt, task)
            if (response.status !in setOf("PENDING", "IN_PROGRESS")) invalid("LISTENING_ITEM_ALREADY_SUBMITTED")

            // 답변과 도움 사용 snapshot을 함께 저장하고 제출한 원문은 더 이상 수정하지 못하게 한다.
            val updated = response.copy(
                answerText = answer.trim(), status = "IN_PROGRESS", revision = response.revision + 1,
                assistanceUsage = assistance,
            )
            val changed =
                replace(session, attempt.copy(tasks = attempt.tasks.map { if (it.id == updated.id) updated else it }))
            updateSession(
                touch(if (assistance.any { it.type == "SHOW_ANSWER" }) reveal(changed, attemptId) else changed, policy),
            )
        }
    }

    suspend fun reveal(userId: Long, sessionId: Long, attemptId: Long): ListeningSessionState {
        val policy = settings.listeningPolicy()
        return work.write(userId) {
            val session = session(userId, sessionId) ?: missing()
            mutable(session, attemptId, policy)
            updateSession(touch(reveal(session, attemptId), policy))
        }
    }

    suspend fun skip(userId: Long, sessionId: Long, attemptId: Long): ListeningSessionState {
        val policy = settings.listeningPolicy()
        return work.write(userId) {
            val session = session(userId, sessionId) ?: missing()
            val attempt = mutable(session, attemptId, policy)
            val skipped = attempt.copy(
                status = "SKIPPED", submittedAt = nowUtc.toString(),
                tasks = attempt.tasks.map { if (it.status == "NOT_SELECTED") it else it.copy(status = "SKIPPED") },
            )
            updateSession(phase(touch(replace(session, skipped), policy), checkNotNull(set(userId, session.setId))))
        }
    }

    suspend fun assistance(userId: Long, sessionId: Long, attemptId: Long, type: String): ListeningSessionState {
        if (type == "SHOW_ANSWER") invalid()
        validateAssistance(listOf(ListeningAssistanceUsage(type)))
        val policy = settings.listeningPolicy()
        return work.write(userId) {
            val session = session(userId, sessionId) ?: missing()
            val attempt = mutable(session, attemptId, policy)
            val tasks = attempt.tasks.map { response ->
                if (response.status == "NOT_SELECTED") response else {
                    val first = response.assistanceUsage.indexOfFirst { it.type == type }
                    val usages = if (first < 0) response.assistanceUsage + ListeningAssistanceUsage(type)
                    else response.assistanceUsage.mapIndexed { index, usage ->
                        if (index == first) usage.copy(count = minOf(usage.count + 1, 100)) else usage
                    }
                    response.copy(assistanceUsage = usages, revision = response.revision + 1)
                }
            }
            updateSession(touch(replace(session, attempt.copy(tasks = tasks)), policy))
        }
    }

    suspend fun playback(userId: Long, sessionId: Long, itemId: Long, attemptId: Long, type: String, eventId: String) {
        val key = eventId.trim()
        if (type !in setOf("NORMAL", "SLOW") || key.isBlank() || key.length > 200) invalid(
            "LISTENING_PLAYBACK_EVENT_INVALID",
        )
        work.write(userId) {
            val session = session(userId, sessionId) ?: missing()
            val attempt = session.attempts.firstOrNull { it.id == attemptId && it.itemId == itemId } ?: invalid(
                "LISTENING_PLAYBACK_EVENT_INVALID",
            )
            if (attempt.submittedAt != null) invalid("LISTENING_PLAYBACK_EVENT_INVALID")
            if (attempt.playbackEvents.containsKey(key)) return@write
            updateSession(replace(session, attempt.copy(playbackEvents = attempt.playbackEvents + (key to type))))
        }
    }

    suspend fun taskAssistance(
        userId: Long, sessionId: Long, attemptId: Long, task: ListeningTaskType,
        usage: List<ListeningAssistanceUsage>,
    ): ListeningSessionState {
        validateAssistance(usage)
        val policy = settings.listeningPolicy()
        return work.write(userId) {
            val session = session(userId, sessionId) ?: missing()
            val attempt = mutable(session, attemptId, policy)
            val response = selected(attempt, task)

            // 정답 공개 도움은 문항 전체를 공개하며 그 외 도움은 해당 Task의 snapshot만 바꾼다.
            val updated = if (usage.any { it.type == "SHOW_ANSWER" }) reveal(session, attemptId)
            else replace(
                session,
                attempt.copy(
                    tasks = attempt.tasks.map {
                        if (it.id == response.id) it.copy(assistanceUsage = usage, revision = it.revision + 1) else it
                    },
                ),
            )
            updateSession(touch(updated, policy))
        }
    }

    suspend fun complete(userId: Long, sessionId: Long): ListeningSessionState {
        val policy = settings.listeningPolicy()
        return work.write(userId) {
            val session = session(userId, sessionId) ?: missing()
            val set = checkNotNull(set(userId, session.setId))
            val synchronized = synchronize(session, set, policy)
            if (synchronized.status == "ABANDONED") return@write synchronized
            val official = synchronized.attempts.filter { it.purpose == "OFFICIAL" }
            if ((1..set.targetItemCount).any { index -> official.none { it.itemIndex == index } } || official.any { it.status !in terminal }) invalid()

            // 호출자가 전달한 경과 시간은 사용하지 않고 공식 시도에서 누적한 기존 합계를 유지한다.
            if (synchronized.status in setOf("IN_PROGRESS", "EVALUATING"))
                updateSession(
                    synchronized.copy(status = "COMPLETED", lastActivityAt = nowUtc.toString()),
                ) else synchronized
        }
    }

    suspend fun retryFailed(userId: Long, sessionId: Long): Triple<Int, Int, Int> {
        val policy = settings.listeningPolicy()
        return work.write(userId) {
            var session = session(userId, sessionId) ?: missing()
            if (session.status !in setOf("COMPLETED", "EVALUATING")) invalid()
            var failed = 0
            var retried = 0
            var exhausted = 0

            // 공식 시도의 실패 Task만 한 transaction에서 예약한다. 성공한 답변·결과는 그대로 보존한다.
            for (original in session.attempts.filter { it.purpose == "OFFICIAL" }) {
                var attempt = original
                for (response in original.tasks.filter { it.status == "EVALUATION_FAILED" }) {
                    failed++
                    if (response.manualRetries >= policy.manualRetryLimit) {
                        exhausted++
                        continue
                    }
                    if (attempt.progressApplied || attempt.manualEvaluationRetries >= policy.manualRetryLimit * 3) invalid()
                    val task = response.copy(
                        status = "EVALUATING", manualRetries = response.manualRetries + 1, evaluationErrorCode = null,
                    )
                    attempt = attempt.copy(
                        status = "EVALUATING", manualEvaluationRetries = attempt.manualEvaluationRetries + 1,
                        tasks = attempt.tasks.map { if (it.id == task.id) task else it },
                    )
                    enqueue(
                        userId, sessionId, "EVALUATE", "listening:response:${task.id}:evaluate:${task.manualRetries}",
                        buildJsonObject {
                            put("attemptId", attempt.id)
                            put("responseId", task.id)
                            put("revision", task.revision)
                        },
                    )
                    retried++
                }
                session = replace(session, attempt)
            }
            if (retried > 0) updateSession(session.copy(status = "EVALUATING", lastActivityAt = nowUtc.toString()))
            Triple(failed, retried, exhausted)
        }
    }

    suspend fun upload(
        userId: Long, sessionId: Long, attemptId: Long, bytes: ByteArray, contentType: String, durationMs: Int,
    ): ListeningSessionState {
        val policy = settings.listeningPolicy()
        ListeningAudioUploadPolicy.validate(
            bytes, contentType, policy.maxAudioFileBytes, durationMs, policy.repeatAudioMaxSeconds,
        )
        return work.write(userId) {
            val session = session(userId, sessionId) ?: missing()
            val attempt = mutable(session, attemptId, policy)
            val response = selected(attempt, ListeningTaskType.REPEAT_AFTER_AUDIO)
            val previous = response.audioId?.let { audio(userId, it) }
            val rerecord = previous != null && previous.deletedAt == null
            if (rerecord && response.rerecordCount >= policy.maxRerecordCount) invalid(
                "LISTENING_RERECORD_LIMIT_EXCEEDED",
            )

            // 새 blob의 소유권과 답변 revision을 같은 commit에 저장하고 이전 녹음만 제거한다.
            val id = allocateId()
            val revision = response.revision + 1
            insertAudio(
                ListeningAudioState(
                    id, userId, response.id, revision, contentType,
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(bytes)
                        .joinToString("") { "%02x".format(it) },
                    bytes, nowUtc.plusDays(policy.userAudioRetentionDays.toLong()), null,
                ),
            )
            val changed = response.copy(
                audioId = id, audioDurationMs = durationMs, revision = revision,
                rerecordCount = response.rerecordCount + if (rerecord) 1 else 0, status = "IN_PROGRESS",
            )
            val saved = updateSession(
                touch(
                    replace(
                        session, attempt.copy(tasks = attempt.tasks.map { if (it.id == response.id) changed else it }),
                    ),
                    policy,
                ),
            )
            if (rerecord) deleteAudio(userId, checkNotNull(previous).id)
            saved
        }
    }

    suspend fun retryEvaluation(
        userId: Long, sessionId: Long, attemptId: Long, task: ListeningTaskType,
    ): ListeningSessionState {
        val policy = settings.listeningPolicy()
        return work.write(userId) {
            val session = session(userId, sessionId) ?: missing()
            val attempt =
                session.attempts.firstOrNull { it.id == attemptId } ?: missing("LANGUAGE_LEARNING_DAILY_ITEM_NOT_FOUND")
            if (session.status !in setOf("COMPLETED", "EVALUATING") &&
                (session.status != "IN_PROGRESS" || expired(session, policy))
            ) invalid()
            val response = selected(attempt, task)
            if (response.status != "EVALUATION_FAILED" || response.manualRetries >= policy.manualRetryLimit ||
                attempt.progressApplied || attempt.manualEvaluationRetries >= policy.manualRetryLimit * 3
            ) invalid()

            // 원래 답변을 그대로 재평가하고 수동 상한·기존 공식 진행도 중복 방지를 유지한다.
            val retried = response.copy(
                status = "EVALUATING", manualRetries = response.manualRetries + 1, evaluationErrorCode = null,
            )
            val changed = attempt.copy(
                status = "EVALUATING", manualEvaluationRetries = attempt.manualEvaluationRetries + 1,
                tasks = attempt.tasks.map { if (it.id == retried.id) retried else it },
            )
            enqueue(
                userId, sessionId, "EVALUATE", "listening:response:${response.id}:evaluate:${retried.manualRetries}",
                buildJsonObject {
                    put("attemptId", attemptId)
                    put("responseId", response.id)
                    put("revision", response.revision)
                },
            )
            updateSession(
                replace(session, changed).copy(
                    status = if (session.status == "COMPLETED") "EVALUATING" else session.status,
                    lastActivityAt = nowUtc.toString(),
                ),
            )
        }
    }

    suspend fun report(
        userId: Long, sessionId: Long, taskId: Long, reason: String, comment: String?, consent: Boolean, key: String,
    ): ListeningReportState {
        if (reason.isBlank() || reason.length > 100 || (comment?.length
                ?: 0) > 2000 || key.isBlank() || key.length > 200
        ) invalid()
        val policy = settings.listeningPolicy()
        return work.write(userId) {
            val session = session(userId, sessionId) ?: missing()
            val attempt = session.attempts.firstOrNull { it.tasks.any { task -> task.id == taskId } } ?: missing(
                "LANGUAGE_LEARNING_DAILY_ITEM_NOT_FOUND",
            )
            val response = attempt.tasks.single { it.id == taskId }
            response.reports.firstOrNull { it.idempotencyKey == key }?.let { return@write it }
            if (response.reports.isNotEmpty()) invalid("LISTENING_REPORT_ALREADY_SUBMITTED")
            if (attempt.answerRevealed || response.status !in setOf("EVALUATED", "NOT_EVALUABLE")) invalid()

            // 동의받은 아직 유효한 녹음만 생성 시점 기준 절대 보존 상한 안에서 연장한다.
            if (consent) {
                val audio = response.audioId?.let { audio(userId, it) }
                if (audio == null || audio.bytes == null || audio.deletedAt != null || audio.retentionUntil < nowUtc)
                    invalid("LISTENING_REPORT_AUDIO_EXPIRED")
                extendAudio(
                    userId, audio.id,
                    minOf(
                        nowUtc.plusDays(policy.reportedAudioRetentionDays.toLong()),
                        LocalDateTime.parse(attempt.startedAt).plusDays(policy.reportedAudioRetentionDays.toLong()),
                    ),
                )
            }
            val report = ListeningReportState(allocateId(), reason, comment, consent, key, nowUtc.toString())
            updateSession(
                replace(
                    session,
                    attempt.copy(
                        tasks = attempt.tasks.map {
                            if (it.id == taskId) it.copy(
                                reports = it.reports + report,
                            ) else it
                        },
                    ),
                ),
            )
            report
        }
    }

    suspend fun submit(userId: Long, sessionId: Long, attemptId: Long, durationMs: Long): ListeningSessionState {
        val policy = settings.listeningPolicy()
        return work.write(userId) {
            val session = session(userId, sessionId) ?: missing()
            val attempt =
                session.attempts.firstOrNull { it.id == attemptId } ?: missing("LANGUAGE_LEARNING_DAILY_ITEM_NOT_FOUND")
            if (attempt.status != "IN_PROGRESS") return@write session
            mutable(session, attemptId, policy)
            val tasks = attempt.tasks.filter { it.status != "NOT_SELECTED" }
            if (tasks.any {
                    if (it.taskType == ListeningTaskType.REPEAT_AFTER_AUDIO) it.audioId == null
                    else it.answerText.isNullOrBlank()
                }) invalid()

            // 수정 가능한 답변 revision을 고정한 뒤 같은 commit에서 평가 작업을 예약한다.
            val changed = attempt.copy(
                status = "EVALUATING", submittedAt = nowUtc.toString(), actualDurationMs = maxOf(0, durationMs),
                tasks = attempt.tasks.map { if (it.status == "NOT_SELECTED") it else it.copy(status = "EVALUATING") },
            )
            tasks.forEach { response ->
                enqueue(
                    userId, sessionId, "EVALUATE",
                    "listening:response:${response.id}:evaluate:${response.manualRetries}",
                    buildJsonObject {
                        put("attemptId", attempt.id)
                        put("responseId", response.id)
                        put("revision", response.revision)
                    },
                )
            }
            val updated = touch(replace(session, changed), policy)
            val set = checkNotNull(set(userId, session.setId))
            updateSession(phase(updated, set))
        }
    }

    suspend fun practice(
        userId: Long, sessionId: Long, itemId: Long, tasks: List<ListeningTaskType>?, idempotencyKey: String?,
    ): ListeningSessionState {
        val policy = settings.listeningPolicy()
        return work.write(userId) {
            val session = session(userId, sessionId) ?: missing()
            val official = session.attempts.firstOrNull { it.itemId == itemId && it.purpose == "OFFICIAL" }
                ?: missing("LANGUAGE_LEARNING_DAILY_ITEM_NOT_FOUND")
            if (official.status !in terminal || session.status != "COMPLETED" &&
                (session.status != "IN_PROGRESS" || expired(session, policy))
            ) invalid()
            val selected = ListeningTaskSelectionPolicy.validate(tasks ?: session.selectedTaskTypes)
            val key = idempotencyKey?.trim()?.takeUnless(String::isBlank) ?: "session:$sessionId:item:$itemId:practice"
            if (key.length > 200) invalid()
            session.attempts.firstOrNull { it.idempotencyKey == key }?.let { existing ->
                if (existing.itemId != itemId || existing.tasks.filter { it.status != "NOT_SELECTED" }
                        .map { it.taskType } != selected)
                    invalid("LISTENING_IDEMPOTENCY_CONFLICT")
                return@write session
            }
            if (session.attempts.count { it.itemId == itemId && it.purpose == "PRACTICE" } >= policy.practiceAttemptLimit)
                invalid("LISTENING_PRACTICE_LIMIT_EXCEEDED")
            val item = checkNotNull(set(userId, session.setId)).items.single { it.id == itemId }
            updateSession(session.copy(attempts = session.attempts + attempt(item, selected, "PRACTICE", 2, key)))
        }
    }

    private fun ListeningTransaction.synchronize(
        session: ListeningSessionState, set: ListeningSetState, policy: ListeningPolicy,
    ): ListeningSessionState {
        if (session.status != "IN_PROGRESS") return session
        if (expired(session, policy)) return updateSession(
            session.copy(status = "ABANDONED", lastActivityAt = nowUtc.toString()),
        )
        val indices = session.attempts.filter { it.purpose == "OFFICIAL" }.map { it.itemIndex }.toSet()
        val missing = playable(set).filter { it.index !in indices }
        if (missing.isEmpty()) return session
        return updateSession(
            session.copy(
                attempts = session.attempts + missing.map {
                    attempt(
                        it, session.selectedTaskTypes, "OFFICIAL", 1, "session:${session.id}:item:${it.id}:official",
                    )
                },
            ),
        )
    }

    private fun ListeningTransaction.reveal(session: ListeningSessionState, attemptId: Long): ListeningSessionState {
        val attempt = session.attempts.single { it.id == attemptId }
        val revealed = attempt.copy(
            answerRevealed = true,
            tasks = attempt.tasks.map {
                if (it.status == "NOT_SELECTED") it else it.copy(
                    status = "NOT_EVALUABLE",
                    assistanceUsage = listOf(ListeningAssistanceUsage("SHOW_ANSWER")),
                )
            },
        )
        return ListeningAttemptFinalizer.finalize(this, replace(session, revealed), attemptId)
    }

    private fun ListeningTransaction.playable(set: ListeningSetState) = set.items.filter { it.status == "READY" }
        .groupBy { it.index }.values.mapNotNull { items -> items.maxByOrNull { it.replacementSequence } }
        .filter { item ->
            item.audioId?.let { audio(set.userId, it) }
                ?.let { it.bytes != null && it.retentionUntil > nowUtc } == true
        }
        .sortedBy { it.index }

    private fun ListeningTransaction.attempt(
        item: ListeningItemState, selected: List<ListeningTaskType>, purpose: String, number: Int, key: String,
    ) =
        ListeningAttemptState(
            allocateId(), item.id, item.index, number, purpose, nowUtc.toString(),
            ListeningTaskType.entries.map {
                ListeningResponseState(
                    allocateId(), it, if (it in selected) "PENDING" else "NOT_SELECTED",
                )
            },
            idempotencyKey = key,
        )

    private fun ListeningTransaction.mutable(
        session: ListeningSessionState, id: Long, policy: ListeningPolicy,
    ): ListeningAttemptState {
        val attempt = session.attempts.firstOrNull { it.id == id } ?: missing("LANGUAGE_LEARNING_DAILY_ITEM_NOT_FOUND")
        if (!(attempt.purpose == "PRACTICE" && session.status == "COMPLETED")) {
            if (session.status != "IN_PROGRESS") invalid()
            if (expired(session, policy)) invalid("LISTENING_SESSION_EXPIRED")
        }
        if (attempt.status != "IN_PROGRESS") invalid("LISTENING_ITEM_ALREADY_SUBMITTED")
        return attempt
    }

    private fun selected(attempt: ListeningAttemptState, task: ListeningTaskType) =
        attempt.tasks.firstOrNull { it.taskType == task && it.status != "NOT_SELECTED" } ?: invalid()

    private fun replace(session: ListeningSessionState, attempt: ListeningAttemptState) =
        session.copy(attempts = session.attempts.map { if (it.id == attempt.id) attempt else it })

    private fun ListeningTransaction.touch(session: ListeningSessionState, policy: ListeningPolicy) =
        if (session.status == "IN_PROGRESS") session.copy(
            lastActivityAt = nowUtc.toString(),
            resumableUntil = nowUtc.plusHours(policy.resumeHours.toLong()).toString(),
        ) else session

    private fun ListeningTransaction.expired(session: ListeningSessionState, policy: ListeningPolicy) =
        session.status == "IN_PROGRESS" && LocalDateTime.parse(session.lastActivityAt)
            .plusHours(policy.resumeHours.toLong()) < nowUtc

    private fun phase(session: ListeningSessionState, set: ListeningSetState): ListeningSessionState {
        val official = session.attempts.filter { it.purpose == "OFFICIAL" }
        if (!(1..set.targetItemCount).all { index -> official.any { it.itemIndex == index } }) return session
        return when {
            official.all { it.status in terminal } -> session.copy(status = "COMPLETED")
            official.all { it.status in terminal || it.status in setOf("EVALUATING", "SUBMITTED") } -> session.copy(
                status = "EVALUATING",
            )

            else -> session
        }
    }

    private fun validateAssistance(values: List<ListeningAssistanceUsage>) {
        if (values.size > 20 || values.any {
                it.type !in setOf(
                    "REPLAY", "SLOW_PLAYBACK", "TOPIC_HINT", "KEYWORD_HINT", "SHOW_ANSWER",
                ) || it.count !in 1..100
            }) invalid()
    }

    private fun key(value: String?) = (value?.trim()?.takeUnless(String::isBlank) ?: UUID.randomUUID()
        .toString()).also { if (it.length > 200) invalid() }

    private fun invalid(code: String = "LISTENING_INVALID_STATE"): Nothing =
        throw LearningBusinessException(code, "Listening 요청 상태가 올바르지 않습니다.")

    private fun missing(code: String = "SESSION_NOT_FOUND"): Nothing =
        throw LearningBusinessException(code, "Listening 학습 기록을 찾을 수 없습니다.")

    private val terminal = setOf("EVALUATED", "NOT_EVALUABLE", "SKIPPED")
}
