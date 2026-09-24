package jp.co.translacat.languagelearning.features.learner.domain.model

import jp.co.translacat.languagelearning.features.learner.domain.exception.LearnerUnavailableException
import java.time.LocalDateTime

/** Core 계정의 복제본이 아니라 LL 내부의 상태·잠금 기준 행이다. */
internal data class Learner(
    val userId: Long,
    val status: String,
    val identityVersion: Long,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    fun requireActive() {
        // 알 수 없는 상태도 허용하지 않는다. 조회 중 계정을 자동 재활성화하지 않는다.
        if (status != "ACTIVE") {
            throw LearnerUnavailableException(userId)
        }
    }
}
