package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingAnswer
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingEvaluationStatus
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingItemRevision
import java.time.LocalDate

/** 설정 조회와 평가 dispatch는 상위 계층; 이 트랜잭션은 재생성·제출 경합을 직렬화한다. */
internal class WritingAnswerState(private val work: WritingSetUnitOfWork) {
    suspend fun pendingToday(userId: Long, itemId: Long, today: LocalDate): Long? = work.read {
        checkNotNull(items.find(userId, itemId)) { "WRITING_ITEM_NOT_FOUND" }
        val answer = checkNotNull(answers.find(userId, itemId, today)) { "WRITING_ANSWER_NOT_FOUND" }
        answer.takeIf {
            it.evaluationStatus == WritingEvaluationStatus.PENDING
        }?.id
    }

    suspend fun submit(
        userId: Long,
        itemId: Long,
        answer: String,
        contentRevision: String?,
        today: LocalDate,
        reviewAvailableDays: Int,
        aiEvaluationEnabled: Boolean,
    ): WritingAnswer {
        require(aiEvaluationEnabled) { "WRITING_EVALUATION_DISABLED" }
        require(answer.isNotBlank()) { "WRITING_ANSWER_REQUIRED" }
        return work.write(userId) {
            val item = items.find(userId, itemId) ?: error("WRITING_ITEM_NOT_FOUND")
            val set = sets.findById(userId, item.setId) ?: error("WRITING_SET_NOT_FOUND")
            require(
                set.generationToken == null || set.generationLeaseUntil == null ||
                    !set.generationLeaseUntil.isAfter(nowUtc) ||
                    set.status !in setOf(
                    jp.co.translacat.languagelearning.features.writing.domain.model.WritingSetStatus.READY,
                    jp.co.translacat.languagelearning.features.writing.domain.model.WritingSetStatus.COMPLETED,
                ),
            ) {
                "WRITING_REGENERATION_IN_PROGRESS"
            }
            if (!contentRevision.isNullOrBlank())
                require(contentRevision == WritingItemRevision.of(item)) { "WRITING_ITEM_STALE" }
            require(
                !today.isBefore(set.learningDate) && !today.isAfter(
                    set.learningDate.plusDays(reviewAvailableDays - 1L)
                ),
            ) {
                "WRITING_REVIEW_EXPIRED"
            }
            val old = answers.find(userId, itemId, today)
            require(
                old == null || old.evaluationStatus == WritingEvaluationStatus.FAILED,
            ) { "WRITING_ANSWER_NOT_ALLOWED" }
            answers.submit(userId, itemId, today, answer.trim(), nowUtc, old)
        }
    }
}
