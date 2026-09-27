package jp.co.translacat.languagelearning.features.listening.application

import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningJob
import kotlinx.coroutines.*

/** 기존 30초 lease 갱신과 정상 종료 시 즉시 재개 예약을 보존한다. 모델 deadline은 바꾸지 않는다. */
internal class ListeningLease(
    private val work: ListeningUnitOfWork, private val claim: ListeningJob, private val heartbeat: Job,
) {
    suspend fun close() {
        withContext(NonCancellable) {
            heartbeat.cancelAndJoin()
            work.write(claim.userId) { release(claim) }
        }
    }

    companion object {
        suspend fun start(work: ListeningUnitOfWork, claim: ListeningJob): ListeningLease {
            val heartbeat =
                CoroutineScope(currentCoroutineContext()).launch(CoroutineName("listening-lease-${claim.id}")) {
                    while (isActive) {
                        delay(30_000)
                        if (!work.write(claim.userId) { renew(claim) }) break
                    }
                }
            return ListeningLease(work, claim, heartbeat)
        }
    }
}
