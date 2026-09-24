package jp.co.translacat.languagelearning.features.learner.domain.repository

import java.time.LocalDateTime
import jp.co.translacat.languagelearning.features.learner.domain.model.Learner

/** 모든 메서드는 호출자가 소유한 동일 트랜잭션 안에서 실행한다. */
internal interface LearnerRepository {
    fun ensureAndLock(userId: Long, nowUtc: LocalDateTime): Learner
}
