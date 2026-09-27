package jp.co.translacat.languagelearning.features.listening.application

import jp.co.translacat.languagelearning.features.growth.application.GrowthProjector
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthActivity
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthChange
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningSessionState
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningScoring
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime

internal object ListeningAttemptFinalizer {
    private val terminalTasks = setOf("EVALUATED", "EVALUATION_FAILED", "NOT_EVALUABLE", "SKIPPED")
    private val terminalAttempts = setOf("EVALUATED", "NOT_EVALUABLE", "SKIPPED")

    fun finalize(
        transaction: ListeningTransaction, session: ListeningSessionState, attemptId: Long,
    ): ListeningSessionState = with(transaction) {
        val attempt = session.attempts.single { it.id == attemptId }
        if (attempt.status in terminalAttempts) return session
        val selected = attempt.tasks.filter { it.status != "NOT_SELECTED" }
        if (selected.isEmpty() || selected.any { it.status !in terminalTasks }) return session

        // 기존 평가 가능한 Task의 평균과 제출 이전 재생 수로 공식 점수를 계산한다.
        val evaluated = selected.mapNotNull { it.evaluation }.filter { it.evaluable && it.score != null }
        val content = evaluated.map { checkNotNull(it.score).toDouble() }.takeIf { it.isNotEmpty() }?.average()
        val independence = if (content != null && attempt.submittedAt != null) ListeningScoring.independence(
            attempt.playbackEvents.values.count { it == "NORMAL" }.toLong(),
            attempt.playbackEvents.values.count { it == "SLOW" }.toLong(),
        ).toDouble() else null
        val eligible = ListeningScoring.progressEligible(
            attempt.purpose == "OFFICIAL", attempt.purpose == "PRACTICE",
            attempt.answerRevealed, attempt.progressApplied, selected.map { it.status },
        )
        var result = session
        val set = checkNotNull(set(session.userId, session.setId))
        if (eligible) {
            // 첫 공식 완료만 진행도와 Growth activity를 같은 transaction에 반영한다.
            result = result.copy(
                completedItemCount = result.completedItemCount + 1,
                evaluatedItemCount = result.evaluatedItemCount + if (evaluated.size == selected.size) 1 else 0,
                actualDurationMs = result.actualDurationMs + maxOf(0, attempt.actualDurationMs),
                lastActivityAt = nowUtc.toString(),
            )
            val completed = minOf(set.targetItemCount, set.completedItemCount + 1)
            updateSet(
                set.copy(
                    completedItemCount = completed,
                    status = if (completed >= set.targetItemCount) "COMPLETED" else set.status,
                ),
            )
            GrowthProjector(growth).apply(
                session.userId,
                GrowthChange.ActivityRecorded(
                    GrowthActivity(
                        userId = session.userId, source = "LISTENING",
                        referenceId = "ll-listening-attempt-${attempt.id}",
                        learningDate = LocalDate.parse(set.learningDate), title = "Daily Listening",
                        durationSeconds = attempt.actualDurationMs / 1000, status = "COMPLETED",
                        startedAt = LocalDateTime.parse(attempt.startedAt), completedAt = nowUtc, createdAt = nowUtc,
                        updatedAt = nowUtc,
                    ),
                    null,
                ),
                nowUtc,
            )
        }
        val finalized = attempt.copy(
            status = if (evaluated.isEmpty()) "NOT_EVALUABLE" else "EVALUATED",
            contentOverallScore = if (independence != null) content else null,
            listeningIndependenceScore = independence,
            overallScore = if (content != null && independence != null) ListeningScoring.adjustedOverall(
                content, independence,
            ).toDouble() else content,
            evaluatedTaskCount = evaluated.size, coverage = evaluated.size.toDouble() / selected.size,
            progressApplied = attempt.progressApplied || eligible,
        )
        result = result.copy(attempts = result.attempts.map { if (it.id == attemptId) finalized else it })
        if (eligible && independence != null) ListeningProfiles.independence(this, set, finalized, independence)
        if (selected.any { it.evaluation?.profileEligible == true } || eligible && independence != null) {
            enqueue(
                session.userId, session.id, "PROFILE", "listening:attempt:${attempt.id}:profile",
                buildJsonObject { put("attemptId", attempt.id) },
            )
        }

        // 모든 목표 문항이 연결되고 공식 시도가 종료된 경우에만 세션을 완료한다.
        val official = result.attempts.filter { it.purpose == "OFFICIAL" }
        if (attempt.purpose == "OFFICIAL" && result.status in setOf("IN_PROGRESS", "EVALUATING") &&
            (1..set.targetItemCount).all { index -> official.any { it.itemIndex == index } } && official.all { it.status in terminalAttempts }
        ) {
            result = result.copy(status = "COMPLETED", lastActivityAt = nowUtc.toString())
        }
        result
    }
}
