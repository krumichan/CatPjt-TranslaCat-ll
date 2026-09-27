package jp.co.translacat.languagelearning.features.writing.application

import java.time.LocalDate

internal data class WritingEvaluationLanguageContext(
    val originLanguage: String,
    val learningLanguage: String,
    val learningDate: LocalDate,
)

/** PENDING 평가만 다시 조회하며 실제 실행 소유권은 평가 lease에 맡긴다. */
internal class WritingEvaluationRecovery(
    private val state: WritingEvaluationState,
    private val worker: WritingEvaluationWorker,
    private val contextForUser: suspend (Long) -> WritingEvaluationLanguageContext,
) {
    suspend fun runOnce(limit: Int = 20): List<Boolean> {
        // 만료되거나 비어 있는 평가 lease만 읽는다. 이미 완료된 답변은 다시 평가하지 않는다.
        val jobs = state.recoverable(limit)
        val results = mutableListOf<Boolean>()

        // 기존 BE처럼 평가 시점의 설정 언어와 학습 날짜를 사용한다.
        for (job in jobs) {
            val context = contextForUser(job.userId)
            results += worker.process(
                job.userId, job.answerId, context.originLanguage,
                context.learningLanguage, context.learningDate,
            )
        }
        return results
    }
}
