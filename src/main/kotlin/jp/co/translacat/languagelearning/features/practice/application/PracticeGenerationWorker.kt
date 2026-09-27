package jp.co.translacat.languagelearning.features.practice.application

import jp.co.translacat.languagelearning.features.practice.domain.PracticeFailure
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionFailure
import kotlinx.coroutines.CancellationException

internal class PracticeGenerationWorker(
    private val state: PracticeGenerationState, private val generator: ReadingGenerationPort,
) {
    suspend fun process(userId: Long, setId: Long) {
        // 짧은 claim 트랜잭션 밖에서 모델을 실행하고 token이 여전히 유효할 때만 결과를 게시한다.
        val claim = state.claim(userId, setId) ?: return
        try {
            state.publish(claim, generator.generate(claim))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ModelExecutionFailure) {
            state.fail(claim, failure.code, failure.retryable)
        } catch (failure: PracticeFailure) {
            state.fail(claim, failure.code, false)
        }
    }

    suspend fun recover() {
        state.recoverable().forEach { (userId, setId) -> process(userId, setId) }
    }
}
