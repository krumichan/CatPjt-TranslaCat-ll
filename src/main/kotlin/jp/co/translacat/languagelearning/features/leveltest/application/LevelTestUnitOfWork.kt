package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.repository.LevelTestRepository
import java.time.LocalDateTime

internal interface LevelTestTransaction {
    val records: LevelTestRepository
    val nowUtc: LocalDateTime
}

internal interface LevelTestUnitOfWork {
    /** userId가 있으면 learner를 먼저 잠근다. 내부 블록에는 JDBC 작업만 허용한다. */
    suspend fun <T> write(userId: Long?, block: LevelTestTransaction.() -> T): T
    suspend fun <T> read(block: LevelTestTransaction.() -> T): T
}
